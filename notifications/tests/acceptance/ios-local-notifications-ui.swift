import XCTest

@MainActor
final class NexaLocalNotificationsAcceptanceTests: XCTestCase {
    func testLocalNotificationSchedulingAndCancellation() {
        let app = XCUIApplication()
        app.launch()

        app.buttons["Request permission"].tap()
        let permissionGranted = app.staticTexts["Permission granted."]
        if !permissionGranted.waitForExistence(timeout: 2) {
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let permissionAlert = springboard.alerts.firstMatch
            if permissionAlert.waitForExistence(timeout: 15) {
                let allowButton = permissionAlert.buttons.allElementsBoundByIndex.first { button in
                    let label = button.label.lowercased()
                    let allows = label.contains("allow") || label.contains("autoriser")
                    let denies = label.contains("don't") || label.contains("don’t") || label.contains("ne pas")
                    return allows && !denies
                }
                XCTAssertNotNil(allowButton, "notification permission prompt must expose an allow action")
                allowButton?.tap()
            }
        }
        XCTAssertTrue(permissionGranted.waitForExistence(timeout: 20))

        app.buttons["Schedule 60 second reminder"].tap()
        XCTAssertTrue(app.staticTexts["Reminder scheduled."].waitForExistence(timeout: 10))

        app.buttons["Cancel reminder"].tap()
        XCTAssertTrue(app.staticTexts["Pending reminder canceled."].waitForExistence(timeout: 5))
    }
}
