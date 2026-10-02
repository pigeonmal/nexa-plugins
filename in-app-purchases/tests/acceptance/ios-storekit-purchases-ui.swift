import XCTest

@MainActor
final class NexaInAppPurchasesAcceptanceTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testConsumableCatalogPurchaseCompletionAndRestore() {
        let app = XCUIApplication()
        app.launch()

        app.buttons["Load store products"].tap()
        XCTAssertTrue(app.staticTexts["Product catalog loaded."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Products loaded: 2"].exists)

        app.buttons["Buy test coins"].tap()
        XCTAssertTrue(app.staticTexts["Purchase completed; delivering coins."].waitForExistence(timeout: 20))

        app.buttons["Complete delivered consumable"].tap()
        XCTAssertTrue(app.staticTexts["Consumable completed."].waitForExistence(timeout: 10))

        app.buttons["Restore and read owned purchases"].tap()
        XCTAssertTrue(app.staticTexts["Store returned 0 owned purchases."].waitForExistence(timeout: 20))
    }
}
