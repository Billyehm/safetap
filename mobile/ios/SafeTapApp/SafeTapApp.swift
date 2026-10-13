import SwiftUI

/// App entry point.
///
/// iOS version assumptions for this scaffold:
/// - Deployment target iOS 16.0. iOS 16 users get the widget deep-link
///   fallback (`safetap://sos` into SosConfirmationView).
/// - The interactive widget button uses AppIntent, which needs iOS 17.
///   On iOS 17 and newer, tapping the SOS button in the widget runs the
///   intent directly without opening the app.
/// - WidgetKit lock screen accessories need iOS 16+, and the rectangular
///   accessory family renders on the Lock Screen and StandBy.
@main
struct SafeTapApp: App {
    @StateObject private var auth = SessionStore()
    @StateObject private var alerts = AlertSession()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(auth)
                .environmentObject(alerts)
        }
    }
}
