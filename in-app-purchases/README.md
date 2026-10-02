# `@nexa/in-app-purchases`

Typed StoreKit 2 and Google Play Billing APIs for digital products.

```nx
plugin "dev.nexa.in-app-purchases" as InAppPurchases

let store = InAppPurchases()

Button("Buy monthly plan") {
    Task.launch(handle: purchaseTask, executor: TaskExecutor.Main) {
        try {
            if (await store.purchase("pro.monthly", null)).status == InAppPurchases.PurchaseStatus.purchased {
                status = "Purchase returned. Verify and deliver it, then complete the transaction."
            }
        } catch {
            case InAppPurchases.InAppPurchaseError.billingUnavailable {
                status = "The store is unavailable."
            }
            else {
                status = "Purchase failed."
            }
        }
    }
}
```

`products(ids)` returns the localized product names, descriptions, display
prices, product kind, and any eligible Android purchase offers. Android offers
include stable offer IDs and their pricing phases. Pass an offer ID from the
latest catalog result to `purchase`; the plugin queries Google Play again before
launching the purchase sheet so it does not reuse stale `ProductDetails`. If an
Android product has multiple eligible offers, choose one explicitly. StoreKit
uses its normal product purchase flow and returns no Android offer list.

`purchase` returns `purchased`, `pending`, or `cancelled`. A pending payment is
not an entitlement and must not be delivered. Successful results contain a
transaction with product IDs, quantity, transaction time, optional expiry, and
platform verification payload. `purchaseUpdated` reports verified StoreKit
transactions and Google Play purchases, including transactions from an active
purchase flow and later pending-purchase transitions. The active `purchase()`
call also returns its immediate result; if the event and result describe the
same transaction, use its transaction ID to process it once.

On iOS, creating the plugin instance also queues verified transactions that
were unfinished when the app last closed. They are emitted once the app
attaches its update handler, so delivery can be completed after relaunch.

Use `ownedPurchases()` to refresh purchases known by the current store account.
`restorePurchases()` calls StoreKit's user-initiated synchronization and then
reads current entitlements; on Android it reads owned purchases from Google
Play. StoreKit's current entitlement sequence excludes consumables. Google Play
returns unconsumed one-time products because the store does not distinguish
consumable from non-consumable products.

The app is responsible for verifying purchases with its secure backend before
granting valuable or account-bound entitlements. `verificationPayload` is the
StoreKit signed transaction JWS on iOS and the Google Play purchase token on
Android. Do not treat the Android token or a client-side success result as
backend verification. Call `complete(transactionID, consumable)` only after
delivering the product or granting the verified entitlement. StoreKit finishes
the transaction; Android consumes a consumable or acknowledges a non-consumable
or subscription. Pending transactions cannot be completed.

Create one `InAppPurchases` instance per app, keep it while purchase updates are
needed, and call `dispose()` when its owning screen/app lifetime ends. Google
Play Billing requires a foreground Activity to show its purchase sheet. The
plugin returns `activityUnavailable` if no host Activity is available.

## Demo app

The cross-platform demo is in `tests/demo/app`. Create matching products in
App Store Connect and Play Console with identifiers:

- `dev.nexa.inapppurchases.demo.coins` — one-time consumable
- `dev.nexa.inapppurchases.demo.pro` — subscription

The demo deliberately does not grant production access based only on the local
purchase result. Connect `verificationPayload` to a trusted backend before
shipping paid content. StoreKit configuration and Play Console license-tester
setup are required for store transaction runtime acceptance.

The iOS acceptance harness is `tests/acceptance/ios-storekit-purchases.sh`.
It accepts `NEXA_IOS_SIMULATOR_ID` to choose a specific booted simulator. On
Xcode 27.0 with the iOS 26.5 Simulator, command-line `xcodebuild test` may not
sync a scheme's local StoreKit configuration to the simulator; the acceptance
flow needs to run through Xcode's IDE path or a toolchain that performs that
sync. See the [Apple Developer Forums report](https://developer.apple.com/forums/thread/826971).

## Platform requirements

- iOS 17 or later, using StoreKit 2.
- Android API 23 or later, using Google Play Billing Library 9.1.0.
- Android purchase UI requires a foreground Nexa Activity and Google Play Store.
- This plugin does not include a receipt-validation backend or alternative
  billing integration.
