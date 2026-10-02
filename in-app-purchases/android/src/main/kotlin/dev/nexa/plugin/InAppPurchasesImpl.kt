package dev.nexa.plugin

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.nexa.core.NexaRuntimeCore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google Play Billing implementation. Keep one instance alive per app. */
public class InAppPurchasesImpl : InAppPurchasesSpec, PurchasesUpdatedListener {
    private val applicationContext = NexaRuntimeCore.context().applicationContext
    private val connectionMutex = Mutex()
    private val purchaseMutex = Mutex()
    private val purchasesByToken = ConcurrentHashMap<String, Purchase>()
    private val billingClient = BillingClient.newBuilder(applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()

    private var pendingPurchase: PendingPurchase? = null
    private var disposed = false

    override var onPurchaseUpdated: ((StoreTransaction) -> Unit)? = null

    override suspend fun products(productIDs: List<String>): List<StoreProduct> {
        ensureActive()
        if (productIDs.any(String::isBlank)) {
            throw InAppPurchaseError.invalidProductIdentifier(productIDs.firstOrNull(String::isBlank).orEmpty())
        }
        if (productIDs.isEmpty()) return emptyList()

        ensureConnected()
        val uniqueIDs = productIDs.distinct()
        val oneTime = queryProductDetails(uniqueIDs, BillingClient.ProductType.INAPP)
        val subscriptions = queryProductDetails(uniqueIDs, BillingClient.ProductType.SUBS)
        val byID = LinkedHashMap<String, StoreProduct>()
        oneTime.forEach { byID.putIfAbsent(it.productId, it.toStoreProduct(ProductKind.oneTime)) }
        subscriptions.forEach { byID.putIfAbsent(it.productId, it.toStoreProduct(ProductKind.subscription)) }
        return byID.values.toList()
    }

    override suspend fun purchase(productID: String, offerID: String?): PurchaseResult {
        ensureActive()
        if (productID.isBlank()) throw InAppPurchaseError.invalidProductIdentifier(productID)

        return purchaseMutex.withLock {
            ensureConnected()
            val details = findProductDetails(productID)
                ?: throw InAppPurchaseError.productNotFound(productID)
            val offer = selectOffer(details, offerID)
            withContext(Dispatchers.Main.immediate) {
                val activity = NexaRuntimeCore.currentActivity()
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: throw InAppPurchaseError.activityUnavailable
                if (pendingPurchase != null) throw InAppPurchaseError.purchaseInProgress

                suspendCancellableCoroutine { continuation ->
                    val request = PendingPurchase(productID, continuation)
                    pendingPurchase = request
                    continuation.invokeOnCancellation {
                        // Keep the pending slot until Google reports the result. The store UI
                        // continues after caller cancellation, so a second flow must not start.
                    }

                    val detailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.token)
                        .build()
                    val flowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(detailsParams))
                        .build()
                    val result = billingClient.launchBillingFlow(activity, flowParams)
                    if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                        pendingPurchase = null
                        if (continuation.isActive) {
                            continuation.resume(PurchaseResult(PurchaseStatus.cancelled, null))
                        }
                    } else if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        pendingPurchase = null
                        if (continuation.isActive) continuation.resumeWithException(result.toPluginError())
                    }
                }
            }
        }
    }

    override suspend fun ownedPurchases(): List<StoreTransaction> {
        ensureActive()
        ensureConnected()
        val oneTime = queryPurchases(BillingClient.ProductType.INAPP)
        val subscriptions = queryPurchases(BillingClient.ProductType.SUBS)
        return (oneTime + subscriptions)
            .distinctBy(Purchase::getPurchaseToken)
            .onEach { purchasesByToken[it.purchaseToken] = it }
            .map(::toStoreTransaction)
            .sortedWith(compareBy(StoreTransaction::purchasedAtMillis, StoreTransaction::id))
    }

    override suspend fun restorePurchases(): List<StoreTransaction> = ownedPurchases()

    override suspend fun complete(transactionID: String, consumable: Boolean) {
        ensureActive()
        if (transactionID.isBlank()) {
            throw InAppPurchaseError.transactionNotFound(transactionID)
        }
        ensureConnected()

        val purchase = purchasesByToken[transactionID]
            ?: (queryPurchases(BillingClient.ProductType.INAPP) + queryPurchases(BillingClient.ProductType.SUBS))
                .firstOrNull { it.purchaseToken == transactionID }
            ?: throw InAppPurchaseError.transactionNotFound(transactionID)
        purchasesByToken[transactionID] = purchase
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            throw InAppPurchaseError.storeError("A pending purchase cannot be completed")
        }

        val result = if (consumable) {
            consumePurchase(transactionID)
        } else if (purchase.isAcknowledged) {
            return
        } else {
            acknowledgePurchase(transactionID)
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw result.toPluginError()
        }
        if (consumable) purchasesByToken.remove(transactionID)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (disposed) return
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            val request = pendingPurchase
            pendingPurchase = null
            if (request?.continuation?.isActive == true) {
                request.continuation.resume(PurchaseResult(PurchaseStatus.cancelled, null))
            }
            return
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            val request = pendingPurchase
            pendingPurchase = null
            if (request?.continuation?.isActive == true) {
                request.continuation.resumeWithException(result.toPluginError())
            }
            return
        }

        val purchaseList = purchases.orEmpty()
        if (purchaseList.isEmpty()) {
            val request = pendingPurchase
            pendingPurchase = null
            if (request?.continuation?.isActive == true) {
                request.continuation.resumeWithException(
                    InAppPurchaseError.storeError("Google Play returned an empty purchase result"),
                )
            }
            return
        }

        val request = pendingPurchase
        var requestCompleted = false
        purchaseList.forEach { purchase ->
            purchasesByToken[purchase.purchaseToken] = purchase
            val transaction = toStoreTransaction(purchase)
            if (!requestCompleted && request != null && request.productID in purchase.products) {
                requestCompleted = true
                if (request.continuation.isActive) {
                    request.continuation.resume(PurchaseResult(transaction.status, transaction))
                }
            }
            onPurchaseUpdated?.invoke(transaction)
        }
        if (requestCompleted) pendingPurchase = null
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingPurchase?.continuation?.cancel(CancellationException("InAppPurchases was disposed"))
        pendingPurchase = null
        onPurchaseUpdated = null
        purchasesByToken.clear()
        billingClient.endConnection()
    }

    private fun ensureActive() {
        if (disposed) throw InAppPurchaseError.billingUnavailable
    }

    private suspend fun ensureConnected() {
        ensureActive()
        connectionMutex.withLock {
            if (billingClient.isReady) return
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    billingClient.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (!continuation.isActive) return
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(result.toPluginError())
                            }
                        }

                        override fun onBillingServiceDisconnected() = Unit
                    })
                }
            }
        }
        ensureActive()
    }

    private suspend fun queryProductDetails(productIDs: List<String>, productType: String): List<ProductDetails> =
        withContext(Dispatchers.Main.immediate) {
            val products = productIDs.map { productID ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productID)
                    .setProductType(productType)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            suspendCancellableCoroutine { continuation ->
                billingClient.queryProductDetailsAsync(params) { result, queryResult ->
                    if (!continuation.isActive) return@queryProductDetailsAsync
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(queryResult.productDetailsList)
                    } else {
                        continuation.resumeWithException(result.toPluginError())
                    }
                }
            }
        }

    private suspend fun queryPurchases(productType: String): List<Purchase> =
        withContext(Dispatchers.Main.immediate) {
            val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
            suspendCancellableCoroutine { continuation ->
                billingClient.queryPurchasesAsync(params) { result, purchases ->
                    if (!continuation.isActive) return@queryPurchasesAsync
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        continuation.resume(purchases)
                    } else {
                        continuation.resumeWithException(result.toPluginError())
                    }
                }
            }
        }

    private suspend fun findProductDetails(productID: String): ProductDetails? {
        queryProductDetails(listOf(productID), BillingClient.ProductType.INAPP)
            .firstOrNull()?.let { return it }
        return queryProductDetails(listOf(productID), BillingClient.ProductType.SUBS).firstOrNull()
    }

    private fun selectOffer(details: ProductDetails, offerID: String?): OfferChoice {
        val choices = details.purchaseOffers()
        if (choices.isEmpty()) {
            throw InAppPurchaseError.storeError("No eligible purchase offers were returned for ${details.productId}")
        }
        if (offerID == null && choices.size > 1) throw InAppPurchaseError.offerSelectionRequired
        return if (offerID == null) choices.first() else {
            choices.firstOrNull { it.id == offerID }
                ?: throw InAppPurchaseError.offerNotFound(offerID)
        }
    }

    private fun ProductDetails.purchaseOffers(): List<OfferChoice> = when (productType) {
        BillingClient.ProductType.SUBS -> subscriptionOfferDetails.orEmpty().map { offer ->
            val stableID = offer.offerId?.let { "${offer.basePlanId}/$it" } ?: offer.basePlanId
            OfferChoice(
                id = stableID,
                token = offer.offerToken,
                phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                    PricingPhase(phase.formattedPrice, phase.billingPeriod, phase.billingCycleCount)
                },
            )
        }
        else -> {
            val offerDetails = oneTimePurchaseOfferDetailsList
                ?: listOfNotNull(oneTimePurchaseOfferDetails)
            offerDetails.mapNotNull { offer ->
                val token = offer.offerToken ?: return@mapNotNull null
                val stableID = offer.purchaseOptionId ?: offer.offerId ?: "default"
                OfferChoice(
                    id = stableID,
                    token = token,
                    phases = listOf(PricingPhase(offer.formattedPrice, null, 0)),
                )
            }
        }
    }

    private fun ProductDetails.toStoreProduct(kind: ProductKind): StoreProduct {
        val offers = purchaseOffers()
        return StoreProduct(
            id = productId,
            title = name,
            description = description,
            displayPrice = offers.firstOrNull()?.phases?.firstOrNull()?.displayPrice.orEmpty(),
            kind = kind,
            offers = offers.map { offer -> ProductOffer(offer.id, offer.phases) },
        )
    }

    private fun toStoreTransaction(purchase: Purchase): StoreTransaction = StoreTransaction(
        id = purchase.purchaseToken,
        productIDs = purchase.products,
        status = if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            PurchaseStatus.pending
        } else {
            PurchaseStatus.purchased
        },
        purchasedAtMillis = purchase.purchaseTime,
        expiresAtMillis = null,
        quantity = purchase.quantity,
        verificationPayload = purchase.purchaseToken,
    )

    private suspend fun consumePurchase(transactionID: String): BillingResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val params = ConsumeParams.newBuilder().setPurchaseToken(transactionID).build()
                billingClient.consumeAsync(params) { result, _ ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }

    private suspend fun acknowledgePurchase(transactionID: String): BillingResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(transactionID).build()
                billingClient.acknowledgePurchase(params) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }

    private fun BillingResult.toPluginError(): InAppPurchaseError =
        if (responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ||
            responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED
        ) {
            InAppPurchaseError.billingUnavailable
        } else {
            InAppPurchaseError.storeError("Google Play Billing $responseCode: $debugMessage")
        }

    private data class OfferChoice(
        val id: String,
        val token: String,
        val phases: List<PricingPhase>,
    )

    private data class PendingPurchase(
        val productID: String,
        val continuation: CancellableContinuation<PurchaseResult>,
    )
}
