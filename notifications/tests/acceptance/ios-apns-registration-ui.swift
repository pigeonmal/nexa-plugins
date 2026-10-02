import XCTest

@MainActor
final class NexaAPNsAcceptanceTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testSimulatorRegistersForAPNsAndPublishesTokenEvent() {
        let app = XCUIApplication()
        app.launch()

        app.buttons["Register for remote notifications"].tap()

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let notificationPrompt = springboard.alerts.firstMatch
        if notificationPrompt.waitForExistence(timeout: 3) {
            let allow = notificationPrompt.buttons.matching(
                NSPredicate(format: "label CONTAINS[c] %@", "Allow"),
            ).firstMatch
            if allow.waitForExistence(timeout: 5) {
                allow.tap()
            }
        }

        let registrationResult = app.staticTexts.matching(
            NSPredicate(
                format: "label == %@ OR label == %@",
                "Remote notification token registered.",
                "Remote notification registration failed.",
            ),
        ).firstMatch
        XCTAssertTrue(registrationResult.waitForExistence(timeout: 45))
        XCTAssertEqual(registrationResult.label, "Remote notification token registered.")
    }
}
