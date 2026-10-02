import Foundation
import StoreKit

@MainActor
public final class InAppPurchasesImpl: InAppPurchasesSpec {
    private var updatesTask: Task<Void, Never>?
    private var disposed = false
    private var emittedTransactionIDs = Set<String>()
    private var emittedTransactionOrder: [String] = []

    public var onPurchaseUpdated: ((StoreTransaction) -> Void)?

    public required init() {
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                guard !Task.isCancelled, let self, !self.disposed else { return }
                guard case .verified(let transaction) = result else { continue }
                self.emitPurchaseUpdate(self.value(for: transaction, jws: result.jwsRepresentation))
            }
        }
    }

    public func products(_ productIDs: [String]) async throws(InAppPurchaseError) -> [StoreProduct] {
        guard !disposed else { throw .billingUnavailable }
        guard productIDs.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            throw .invalidProductIdentifier(productID: "")
        }
        do {
            var seen = Set<String>()
            let uniqueIDs = productIDs.filter { seen.insert($0).inserted }
            return try await Product.products(for: uniqueIDs).map { product in
                StoreProduct(
                    id: product.id,
                    title: product.displayName,
                    description: product.description,
                    displayPrice: product.displayPrice,
                    kind: kind(for: product.type),
                    offers: []
                )
            }
        } catch {
            throw .storeError(message: error.localizedDescription)
        }
    }

    public func purchase(_ productID: String, _ offerID: String?) async throws(InAppPurchaseError) -> PurchaseResult {
        guard !disposed else { throw .billingUnavailable }
        guard !productID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw .invalidProductIdentifier(productID: productID)
        }
        guard offerID == nil else { throw .offerNotFound(offerID: offerID ?? "") }

        do {
            guard let product = try await Product.products(for: [productID]).first else {
                throw InAppPurchaseError.productNotFound(productID: productID)
            }

            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    throw InAppPurchaseError.verificationFailed
                }
                let value = value(for: transaction, jws: verification.jwsRepresentation)
                emitPurchaseUpdate(value)
                return PurchaseResult(
                    status: .purchased,
                    transaction: value
                )
            case .pending:
                return PurchaseResult(status: .pending, transaction: nil)
            case .userCancelled:
                return PurchaseResult(status: .cancelled, transaction: nil)
            @unknown default:
                throw InAppPurchaseError.storeError(message: "StoreKit returned an unknown purchase result")
            }
        } catch let error as InAppPurchaseError {
            throw error
        } catch {
            throw .storeError(message: error.localizedDescription)
        }
    }

    public func ownedPurchases() async throws(InAppPurchaseError) -> [StoreTransaction] {
        guard !disposed else { throw .billingUnavailable }
        var transactions: [StoreTransaction] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            transactions.append(value(for: transaction, jws: result.jwsRepresentation))
        }
        return transactions
    }

    public func restorePurchases() async throws(InAppPurchaseError) -> [StoreTransaction] {
        guard !disposed else { throw .billingUnavailable }
        do {
            try await AppStore.sync()
            return try await ownedPurchases()
        } catch let error as InAppPurchaseError {
            throw error
        } catch {
            throw .storeError(message: error.localizedDescription)
        }
    }

    public func complete(_ transactionID: String, _ consumable: Bool) async throws(InAppPurchaseError) {
        guard !disposed else { throw .billingUnavailable }
        guard let id = UInt64(transactionID) else {
            throw .transactionNotFound(transactionID: transactionID)
        }
        _ = consumable // StoreKit finishes both consumable and non-consumable transactions.

        for await result in Transaction.unfinished {
            guard case .verified(let transaction) = result, transaction.id == id else { continue }
            await transaction.finish()
            return
        }
        throw .transactionNotFound(transactionID: transactionID)
    }

    public func dispose() {
        guard !disposed else { return }
        disposed = true
        updatesTask?.cancel()
        updatesTask = nil
        onPurchaseUpdated = nil
    }

    private func kind(for type: Product.ProductType) -> ProductKind {
        switch type {
        case .consumable, .nonConsumable:
            return .oneTime
        case .autoRenewable, .nonRenewable:
            return .subscription
        default:
            return .oneTime
        }
    }

    private func value(for transaction: Transaction, jws: String) -> StoreTransaction {
        StoreTransaction(
            id: String(transaction.id),
            productIDs: [transaction.productID],
            status: .purchased,
            purchasedAtMillis: Int64(transaction.purchaseDate.timeIntervalSince1970 * 1_000),
            expiresAtMillis: transaction.expirationDate.map { Int64($0.timeIntervalSince1970 * 1_000) },
            quantity: Int32(transaction.purchasedQuantity),
            verificationPayload: jws
        )
    }

    private func emitPurchaseUpdate(_ transaction: StoreTransaction) {
        guard emittedTransactionIDs.insert(transaction.id).inserted else { return }
        emittedTransactionOrder.append(transaction.id)
        if emittedTransactionOrder.count > 128 {
            emittedTransactionIDs.remove(emittedTransactionOrder.removeFirst())
        }
        onPurchaseUpdated?(transaction)
    }
}
