import XCTest

@MainActor
final class NexaMapsAcceptanceTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testSelectingPinEmitsItsTypedIdentifier() {
        let app = XCUIApplication()
        app.launch()

        let eiffelTower = app.buttons["Eiffel Tower"]
        XCTAssertTrue(
            eiffelTower.waitForExistence(timeout: 30),
            "MapKit did not expose the Eiffel Tower annotation as an accessible button.\n\(app.debugDescription)",
        )
        eiffelTower.tap()

        XCTAssertTrue(app.staticTexts["Selected eiffel"].waitForExistence(timeout: 10))
    }
}
