import Foundation

/// Every backend call in the app flows through this one file.
/// Change baseURL when pointing at production or staging.
enum AppConfig {
    /// Local dev backend. Update to your production host before release.
    static let baseURL = URL(string: "http://localhost:8000")!

    /// How often we push GPS coordinates to the backend while an alert is active.
    static let locationPushInterval: TimeInterval = 15

    /// Deep link used by the iOS 16 widget fallback (tapping the widget opens the app here).
    static let sosDeepLink = URL(string: "safetap://sos")!

    /// Max emergency contacts allowed on the free tier.
    static let freeTierContactLimit = 2

    /// URL scheme registered in Info.plist so iOS 16 widgets can deep link in.
    static let urlScheme = "safetap"
}
