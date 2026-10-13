import AppIntents
import CoreLocation
import WidgetKit

/// The SOS button intent. iOS 17+ only: this is what makes the widget button
/// interactive, firing the SOS without opening the app.
///
/// Honest note on "press-and-hold": a widget button cannot detect a finger
/// staying down, iOS only tells us the tap happened. So the spec's 3-second
/// hold is adapted into a 3-second arming window. The flow:
///
///   tap -> ARMING (3s, red pulsing, Cancel visible)
///        -> COUNTDOWN (5s, cancellable, Cancel visible)
///        -> SOS fires, contacts alerted, widget shows "SOS sent"
///
/// Tapping Cancel at any point before the fire stops everything. The widget
/// timeline reloads at each phase change so the user always sees the state.
@available(iOS 17, *)
struct SosIntent: AppIntent {
    static var title: LocalizedStringResource = "Send SOS"
    static var description = IntentDescription("Alerts your emergency contacts with your live location.")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        guard let token = TokenBridge.loadToken() else {
            throw SosIntentError.notLoggedIn
        }

        // Phase 1: 3-second arming window (the adapted press-and-hold).
        WidgetSharedState.beginArming()
        WidgetCenter.shared.reloadAllTimelines()

        for _ in 0..<30 {
            try await Task.sleep(nanoseconds: 100_000_000) // 0.1s ticks
            if WidgetSharedState.cancelled || Task.isCancelled {
                WidgetSharedState.reset()
                WidgetCenter.shared.reloadAllTimelines()
                return .result()
            }
        }

        // Phase 2: 5-second cancellable countdown.
        WidgetSharedState.beginCountdown()
        WidgetCenter.shared.reloadAllTimelines()

        for _ in 0..<5 {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            if WidgetSharedState.cancelled || Task.isCancelled {
                WidgetSharedState.reset()
                WidgetCenter.shared.reloadAllTimelines()
                return .result()
            }
        }

        // Phase 3: fire. One quick GPS fix, best effort.
        let fix = await WidgetLocation.oneShot(timeout: 8)
        let response = try await fireAlert(token: token, location: fix)

        WidgetSharedState.markSent(alertID: response.id, trackingURL: response.trackingURL)
        WidgetSharedState.cancelled = false
        WidgetCenter.shared.reloadAllTimelines()

        // Hand the 15-second streaming job to the app: when it next opens or
        // foregrounds, it sees activeAlertID and starts LocationStreamer.
        // The widget itself cannot stream in the background.
        return .result()
    }

    private func fireAlert(token: String, location: CLLocation?) async throws -> (id: String, trackingURL: String?) {
        var payload: [String: Any] = [:]
        if let location {
            payload["lat"] = location.coordinate.latitude
            payload["lon"] = location.coordinate.longitude
        }
        var request = URLRequest(url: WidgetConfig.baseURL.appendingPathComponent("api/alerts"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let (data, urlResponse) = try await URLSession.shared.data(for: request)
        guard let http = urlResponse as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SosIntentError.backendFailed
        }
        struct AlertResponse: Decodable { var id: String; var trackingURL: String? }
        let decoded = try JSONDecoder().decode(AlertResponse.self, from: data)
        return (decoded.id, decoded.trackingURL)
    }
}

@available(iOS 17, *)
enum SosIntentError: Error, CustomLocalizedStringResourceConvertible {
    case notLoggedIn
    case backendFailed

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .notLoggedIn: return "Please open SafeTap and sign in first."
        case .backendFailed: return "Could not reach the SafeTap server."
        }
    }
}

/// Cancels an in-progress arm or countdown. Wired to the Cancel button the
/// widget shows during the arming and countdown phases.
@available(iOS 17, *)
struct CancelSosIntent: AppIntent {
    static var title: LocalizedStringResource = "Cancel SOS"
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        WidgetSharedState.cancelled = true
        WidgetSharedState.reset()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

/// Best-effort single GPS fix inside the widget extension.
///
/// Caveat: location permission in an extension is separate from the app's.
/// If the user never granted the widget location access, this returns nil
/// fast and the SOS still fires without coordinates. The backend keeps the
/// last known position, and the app's 15-second streamer takes over on
/// next foreground for live updates.
@available(iOS 17, *)
private enum WidgetLocation {
    static func oneShot(timeout: TimeInterval) async -> CLLocation? {
        await withCheckedContinuation { continuation in
            let manager = CLLocationManager()
            let delegate = WidgetLocationDelegate { location in
                continuation.resume(returning: location)
            }
            manager.delegate = delegate
            manager.desiredAccuracy = kCLLocationAccuracyBest
            // Keep the delegate alive until the callback or the timeout fires.
            objc_setAssociatedObject(manager, "delegate", delegate, .OBJC_ASSOCIATION_RETAIN)
            manager.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) {
                delegate.timedOut()
            }
        }
    }
}

@available(iOS 17, *)
private final class WidgetLocationDelegate: NSObject, CLLocationManagerDelegate {
    private let completion: (CLLocation?) -> Void
    private var done = false

    init(completion: @escaping (CLLocation?) -> Void) {
        self.completion = completion
    }

    private func finish(with location: CLLocation?) {
        guard !done else { return }
        done = true
        completion(location)
    }

    func timedOut() {
        finish(with: nil)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        finish(with: locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(with: nil)
    }
}
