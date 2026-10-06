# `dev.nexa.in-app-purchases`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-StoreKit%202%20%2F%20Google%20Play%20Billing-green.svg)](https://developer.apple.com/storekit/)

Unified digital commerce, consumables, non-consumables, and auto-renewable subscriptions.

Backed by Apple **StoreKit 2** on iOS and **Google Play Billing Library 9.1.0** on Android. Transactions expose platform verification payloads; grant entitlements only after your app or service validates ownership.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/in-app-purchases" as InAppPurchases

app InAppPurchasesDemo {
    let store = InAppPurchases()

    state operationTask: TaskHandle? = null
    state status: String = "Load store products to begin."
    state productCount: Int32 = 0
    state transactionID: String = ""
    state verificationPayload: String = ""
    state hasVerificationPayload: Bool = false

    body {
        OnAppear {
            store.purchaseUpdated { transaction ->
                transactionID = transaction.id
                verificationPayload = transaction.verificationPayload
                hasVerificationPayload = true
                Log.info(message: "NEXA_IAP_PURCHASE_UPDATED \(transaction.id)")
            }
        }
        OnDisappear {
            store.dispose()
        }

        Column(spacing: 12, padding: 20) {
            Text("In-App Purchases")
            Text(status)
            if hasVerificationPayload {
                Text("Store verification payload captured for backend validation")
            }
            Text("Products loaded: \(productCount)")

            Button("Load store products") {
                Task.launch(handle: operationTask, executor: TaskExecutor.Main) {
                    try {
                        productCount = (await store.products([
                            "dev.nexa.inapppurchases.demo.coins",
                            "dev.nexa.inapppurchases.demo.pro",
                        ])).count
                        status = "Product catalog loaded."
                        Log.info(message: "NEXA_IAP_PRODUCT_CATALOG_LOADED \(productCount)")
                    } catch {
                        case InAppPurchases.InAppPurchaseError.billingUnavailable {
                            status = "The store is unavailable."
                            Log.info(message: "NEXA_IAP_BILLING_UNAVAILABLE")
                        }
                        else {
                            status = "Store product lookup failed."
                            Log.info(message: "NEXA_IAP_PRODUCT_LOOKUP_FAILED")
                        }
                    }
                }
            }

            Button("Buy test coins") {
                Task.launch(handle: operationTask, executor: TaskExecutor.Main) {
                    try {
                        if (await store.purchase("dev.nexa.inapppurchases.demo.coins", null)).status == InAppPurchases.PurchaseStatus.purchased {
                            status = "Purchase received; verify the store payload before granting coins."
                            Log.info(message: "NEXA_IAP_PURCHASE_RESULT_PURCHASED")
                        } else {
                            status = "Purchase is pending or cancelled; no coins were granted."
                        }
                    } catch {
                        case InAppPurchases.InAppPurchaseError.purchaseInProgress {
                            status = "Another purchase is still in progress."
                        }
                        else {
                            status = "Purchase could not be completed."
                        }
                    }
                }
            }

            Button("Complete delivered consumable") {
                if transactionID != "" {
                    Task.launch(handle: operationTask, executor: TaskExecutor.Main) {
                        try {
                            // Call only after your backend verifies verificationPayload and the purchase is delivered.
                            await store.complete(transactionID, true)
                            status = "Consumable completed."
                            Log.info(message: "NEXA_IAP_CONSUMABLE_COMPLETED")
                            transactionID = ""
                        } catch {
                            case InAppPurchases.InAppPurchaseError.transactionNotFound(id) {
                                status = "Transaction not found: \(id)"
                            }
                            else {
                                status = "Transaction completion failed."
                            }
                        }
                    }
                }
            }

            Button("Restore and read owned purchases") {
                Task.launch(handle: operationTask, executor: TaskExecutor.Main) {
                    try {
                        productCount = (await store.restorePurchases()).count
                        status = "Store returned \(productCount) owned purchases."
                        Log.info(message: "NEXA_IAP_RESTORE_COUNT \(productCount)")
                    } catch {
                        case InAppPurchases.InAppPurchaseError.billingUnavailable {
                            status = "The store is unavailable."
                            Log.info(message: "NEXA_IAP_BILLING_UNAVAILABLE")
                        }
                        else {
                            status = "Restore failed."
                        }
                    }
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `InAppPurchases` handle

| Constructor | Signature | Description |
|---|---|---|
| `InAppPurchases` | `InAppPurchases()` | Creates the store client and registers transaction updates. |

Create one billing coordinator for the app. Call `complete` only after the purchase has been delivered or the entitlement granted.


#### Methods

| Method | Return Type | Description |
|---|---|---|
| `products(productIDs: Array<String>)` | `async -> Array<StoreProduct> throws InAppPurchaseError` | Queries store catalogs for localized titles, descriptions, and regional currencies |
| `purchase(productID: String, offerID: String?)` | `async -> PurchaseResult throws InAppPurchaseError` | Triggers the OS payment sheet. `offerID` selects an Android Play offer; the current StoreKit 2 implementation returns no offers and rejects non-null offer IDs. |
| `ownedPurchases()` | `async -> Array<StoreTransaction> throws InAppPurchaseError` | Returns active subscriptions and non-consumable entitlements currently owned |
| `restorePurchases()` | `async -> Array<StoreTransaction> throws InAppPurchaseError` | Runs `AppStore.sync()` on iOS and queries current owned purchases from Google Play on Android. |
| `complete(transactionID: String, consumable: Bool)` | `async -> Void throws InAppPurchaseError` | Confirms delivery to StoreKit 2 (`finish()`) or Google Play (`acknowledge()` / `consume()`) |
| `dispose()` | `Void` | Unregisters billing client listeners and detaches background observers |

#### Events

| Event | Payload | Description |
|---|---|---|
| `purchaseUpdated` | `transaction: StoreTransaction` | Fired when out-of-band purchases arrive (e.g. Ask to Buy approvals, renewals) |

---

### Data Structures & Enums

#### `ProductKind`

| Case | Description |
|---|---|
| `oneTime` | Consumable products and permanent non-consumable unlocks. |
| `subscription` | Recurring subscription product. |

#### `PurchaseStatus`

| Case | Meaning |
|---|---|
| `purchased` | StoreKit verified the iOS transaction; Google Play reported the purchase as purchased. Android entitlement verification still belongs to your app's service. |
| `pending` | Deferred payment (for example, parental approval is required). |
| `cancelled` | The user dismissed the payment sheet without completing the purchase. |

#### `StoreProduct`
| Field | Type | Description |
|---|---|---|
| `id` | `String` | Product SKU / Identifier registered in App Store Connect or Google Play Console |
| `title` | `String` | Localized product name |
| `description` | `String` | Localized product marketing description |
| `displayPrice` | `String` | Formatted price string with local currency symbol (e.g. `"$9.99"`, `"€8,99"`) |
| `kind` | `ProductKind` | Product monetization model (`oneTime` or `subscription`) |
| `offers` | `Array<ProductOffer>` | Eligible Play offers; the current StoreKit 2 implementation returns an empty array. |

#### `StoreTransaction`
| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique transaction identifier |
| `productIDs` | `Array<String>` | Products included in this transaction |
| `status` | `PurchaseStatus` | Current execution status |
| `purchasedAtMillis` | `Int64` | Purchase timestamp in Unix epoch milliseconds |
| `expiresAtMillis` | `Int64?` | Expiration date for active subscriptions |
| `quantity` | `Int32` | Purchased unit quantity |
| `verificationPayload` | `String` | Platform purchase-verification data: a JWS payload from StoreKit 2 or a purchase token from Google Play. |

#### `PricingPhase`

| Field | Type | Description |
|---|---|---|
| `displayPrice` | `String` | Localized price for this billing phase. |
| `billingPeriod` | `String?` | Platform billing period when supplied. |
| `billingCycleCount` | `Int32` | Number of cycles in this phase. |

#### `ProductOffer`

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Store offer identifier. |
| `pricingPhases` | `Array<PricingPhase>` | Ordered price phases for an Android Play offer. |

#### `PurchaseResult`

| Field | Type | Description |
|---|---|---|
| `status` | `PurchaseStatus` | Result state: purchased, pending, or cancelled. |
| `transaction` | `StoreTransaction?` | Transaction associated with the result when the store provides one. |

---

### Error Handling (`InAppPurchaseError`)

| Variant | Description |
|---|---|
| `billingUnavailable` | In-app purchases disabled in device settings or Play Store unavailable |
| `invalidProductIdentifier(productID: String)` | Product SKU format is invalid |
| `productNotFound(productID: String)` | Product ID was not returned by the platform store. |
| `offerNotFound(offerID: String)` | Requested Play offer does not exist, or a non-null offer ID was passed to the current iOS backend. |
| `offerSelectionRequired` | Google Play returned multiple eligible offers and the caller did not select one. |
| `activityUnavailable` | Android UI Activity unavailable to host billing sheet |
| `purchaseInProgress` | Another transaction is already undergoing checkout |
| `transactionNotFound(transactionID: String)` | Transaction ID not found in local cache |
| `verificationFailed` | StoreKit 2 returned an unverified iOS transaction. Google Play purchase tokens are not verified by this client plugin. |
| `storeError(message: String)` | Underlying Apple or Google Play store exception |

## 3. Transaction verification and completion

The plugin returns platform transaction data; it does not provide server-side entitlement validation. On iOS, `verificationPayload` is StoreKit 2's JWS representation of a verified transaction. On Android, it is the Google Play purchase token, which must be verified by your backend with Google Play before granting access.

| Step | App responsibility |
|---|---|
| 1. Receive purchase | Handle the `PurchaseResult` and `purchaseUpdated` event; keep the `transactionID` and `verificationPayload`. |
| 2. Verify ownership | Send the payload to your authenticated backend over HTTPS. The backend verifies with the relevant store and grants the entitlement idempotently. |
| 3. Deliver | Update the user's entitlement only after your backend confirms the verified purchase. |
| 4. Complete | Call `complete(transactionID, consumable)` after delivery; this finishes the StoreKit transaction on iOS or acknowledges/consumes the purchase on Android. |

Do not grant consumables from the client's `PurchaseStatus.purchased` value alone. The plugin's `complete` method acknowledges a transaction; it does not verify the purchase for your service.
