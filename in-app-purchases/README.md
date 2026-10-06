# `@nexa/in-app-purchases`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-StoreKit%202%20%2F%20Google%20Play%20Billing-green.svg)](https://developer.apple.com/storekit/)

Unified digital commerce, consumables, non-consumables, and auto-renewable subscriptions.

Backed directly by Apple **StoreKit 2** on iOS and **Google Play Billing Library 7.x** on Android. Features cryptographic receipt verification, server-side JWS payload verification, and transaction completion lifecycle hooks.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.in-app-purchases" as IAP

component SubscriptionPaywallScreen() {
    let store = IAP.InAppPurchases()
    state availableProducts: Array<IAP.StoreProduct> = []
    state isSubscribed: Bool = false

    onAppear(() => {
        loadProducts()
        store.onPurchaseUpdated((transaction) => {
            handleTransaction(transaction)
        })
    })

    onDisappear(() => {
        store.dispose()
    })

    fn loadProducts() {
        try {
            availableProducts = await store.products(["pro_monthly_sub", "pro_annual_sub"])
            let owned = await store.ownedPurchases()
            isSubscribed = owned.count > 0
        } catch IAP.InAppPurchaseError as err {
            print("Store load failed: \(err)")
        }
    }

    fn buySubscription(product: IAP.StoreProduct) {
        try {
            let result = await store.purchase(product.id, offerID: null)
            if result.status == IAP.PurchaseStatus.purchased, let tx = result.transaction {
                handleTransaction(tx)
            }
        } catch IAP.InAppPurchaseError as err {
            print("Purchase failed: \(err)")
        }
    }

    fn handleTransaction(tx: IAP.StoreTransaction) {
        // 1. Grant entitlements in app state
        isSubscribed = true
        // 2. Finalize transaction with the platform app store
        try {
            await store.complete(tx.id, consumable: false)
        } catch IAP.InAppPurchaseError as err {
            print("Failed to finish transaction: \(err)")
        }
    }

    VStack(spacing: 16) {
        Text(isSubscribed ? "You have Pro Access!" : "Upgrade to Pro", size: 20, weight: "bold")
        FastList(availableProducts) { product in
            HStack {
                VStack(alignment: .leading) {
                    Text(product.title, weight: "bold")
                    Text(product.description, size: 12)
                }
                Spacer()
                Button(product.displayPrice, action: () => { buySubscription(product) })
            }
        }
    }
}
```

---

## 2. API Reference

### `InAppPurchases` Native Class

Single application-wide billing coordinator.

```nexa
native class InAppPurchases {
    init()
}
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `products(productIDs: Array<String>)` | `Array<StoreProduct>` | Queries store catalogs for localized titles, descriptions, and regional currencies |
| `purchase(productID: String, offerID: String?)` | `PurchaseResult` | Triggers OS payment sheet. `offerID` specifies introductory pricing or promo offers. |
| `ownedPurchases()` | `Array<StoreTransaction>` | Returns active subscriptions and non-consumable entitlements currently owned |
| `restorePurchases()` | `Array<StoreTransaction>` | Forces App Store / Google Play account sync to re-fetch historical transactions |
| `complete(transactionID: String, consumable: Bool)` | `Void` | Confirms delivery to StoreKit 2 (`finish()`) or Google Play (`acknowledge()` / `consume()`) |
| `dispose()` | `Void` | Unregisters billing client listeners and detaches background observers |

#### Events

| Event | Payload | Description |
|---|---|---|
| `purchaseUpdated` | `transaction: StoreTransaction` | Fired when out-of-band purchases arrive (e.g. Ask to Buy approvals, renewals) |

---

### Data Structures & Enums

#### `ProductKind`
- `oneTime`: Consumable coins/gems or permanent non-consumable feature unlocks.
- `subscription`: Recurring auto-renewable subscription with renewal periods.

#### `PurchaseStatus`
- `purchased`: Payment succeeded and verified.
- `pending`: Deferred payment (e.g. parental approval required).
- `cancelled`: User dismissed payment sheet without charging card.

#### `StoreProduct`
| Field | Type | Description |
|---|---|---|
| `id` | `String` | Product SKU / Identifier registered in App Store Connect or Google Play Console |
| `title` | `String` | Localized product name |
| `description` | `String` | Localized product marketing description |
| `displayPrice` | `String` | Formatted price string with local currency symbol (e.g. `"$9.99"`, `"€8,99"`) |
| `kind` | `ProductKind` | Product monetization model (`oneTime` or `subscription`) |
| `offers` | `Array<ProductOffer>` | Subscription introductory offers or discount tiers |

#### `StoreTransaction`
| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique transaction identifier |
| `productIDs` | `Array<String>` | Products included in this transaction |
| `status` | `PurchaseStatus` | Current execution status |
| `purchasedAtMillis` | `Int64` | Purchase timestamp in Unix epoch milliseconds |
| `expiresAtMillis` | `Int64?` | Expiration date for active subscriptions |
| `quantity` | `Int32` | Purchased unit quantity |
| `verificationPayload` | `String` | Cryptographic signed payload (JWS token in StoreKit 2, purchase token on Android) |

---

### Error Handling (`InAppPurchaseError`)

| Variant | Description |
|---|---|
| `billingUnavailable` | In-app purchases disabled in device settings or Play Store unavailable |
| `invalidProductIdentifier(productID: String)` | Product SKU format is invalid |
| `productNotFound(productID: String)` | Product SKU not found in store catalog |
| `offerNotFound(offerID: String)` | Requested subscription discount offer does not exist |
| `offerSelectionRequired` | Subscription requires explicit offer selection |
| `activityUnavailable` | Android UI Activity unavailable to host billing sheet |
| `purchaseInProgress` | Another transaction is already undergoing checkout |
| `transactionNotFound(transactionID: String)` | Transaction ID not found in local cache |
| `verificationFailed` | JWS signature verification or purchase token validation failed |
| `storeError(message: String)` | Underlying Apple or Google Play store exception |
