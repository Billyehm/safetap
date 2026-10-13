import Foundation

/// State shared between the main app and the widget extension through an
/// App Group. Both targets must enable the App Groups capability with the
/// same identifier.
///
/// Xcode setup needed (not code):
/// - App Group: group.com.safetap.app on both targets.
/// - Keychain Sharing with the same access group, so the widget extension
///   can read the bearer token saved by the app.
enum WidgetSharedState {
    static let appGroupID = "group.com.safetap.app"

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroupID) ?? .standard
    }

    private enum Key {
        static let phase = "sos.phase"            // idle | arming | countdown | sent
        static let armedAt = "sos.armedAt"        // Date the intent started
        static let countdownEndsAt = "sos.countdownEndsAt"
        static let cancelled = "sos.cancelled"    // flipped by the Cancel intent
        static let activeAlertID = "sos.alertID"  // set when the SOS fires
        static let trackingURL = "sos.trackingURL"
    }

    enum Phase: String {
        case idle, arming, countdown, sent
    }

    static var phase: Phase {
        get { Phase(rawValue: defaults.string(forKey: Key.phase) ?? "") ?? .idle }
        set { defaults.set(newValue.rawValue, forKey: Key.phase) }
    }

    static var cancelled: Bool {
        get { defaults.bool(forKey: Key.cancelled) }
        set { defaults.set(newValue, forKey: Key.cancelled) }
    }

    static var armedAt: Date? {
        get { defaults.object(forKey: Key.armedAt) as? Date }
        set { defaults.set(newValue, forKey: Key.armedAt) }
    }

    static var countdownEndsAt: Date? {
        get { defaults.object(forKey: Key.countdownEndsAt) as? Date }
        set { defaults.set(newValue, forKey: Key.countdownEndsAt) }
    }

    static var activeAlertID: String? {
        get { defaults.string(forKey: Key.activeAlertID) }
        set { defaults.set(newValue, forKey: Key.activeAlertID) }
    }

    static var trackingURL: String? {
        get { defaults.string(forKey: Key.trackingURL) }
        set { defaults.set(newValue, forKey: Key.trackingURL) }
    }

    /// Seconds left in the 5-second countdown, for the widget to render.
    static var countdownRemaining: Int {
        guard let ends = countdownEndsAt else { return 0 }
        return max(0, Int(ends.timeIntervalSinceNow.rounded(.up)))
    }

    static func beginArming() {
        cancelled = false
        activeAlertID = nil
        armedAt = Date()
        countdownEndsAt = nil
        phase = .arming
    }

    static func beginCountdown() {
        countdownEndsAt = Date().addingTimeInterval(5)
        phase = .countdown
    }

    static func markSent(alertID: String, trackingURL: String?) {
        activeAlertID = alertID
        self.trackingURL = trackingURL
        phase = .sent
    }

    static func reset() {
        phase = .idle
        cancelled = false
        armedAt = nil
        countdownEndsAt = nil
        activeAlertID = nil
        trackingURL = nil
    }
}
