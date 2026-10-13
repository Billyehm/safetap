import Foundation
import CoreLocation

/// Holds the state of an in-progress SOS across views.
@MainActor
final class AlertSession: ObservableObject {
    static let shared = AlertSession()
    private init() {}

    @Published var activeAlertID: String?
    @Published var trackingURL: String?
    @Published var lastError: String?

    var isAlertActive: Bool { activeAlertID != nil }

    /// Called when the app launches or foregrounds. If the widget fired an
    /// SOS while the app was closed, we adopt it here so the 15-second
    /// location streamer starts and the ActiveAlertView appears.
    /// Lives in the app target; reads the App Group state the widget wrote.
    func adoptWidgetAlertIfNeeded() {
        guard activeAlertID == nil else { return }
        let defaults = UserDefaults(suiteName: "group.com.safetap.app")
        guard let alertID = defaults?.string(forKey: "sos.alertID"),
              defaults?.string(forKey: "sos.phase") == "sent" else { return }
        activeAlertID = alertID
        trackingURL = defaults?.string(forKey: "sos.trackingURL")
        LocationStreamer.shared.start(alertID: alertID)
    }

    /// Full SOS flow used by the in-app confirm view and the iOS 16 deep link.
    /// Grabs one quick GPS fix, triggers the alert on the backend, then
    /// hands off to the 15-second location streamer.
    func triggerSOS() async {
        lastError = nil
        do {
            let fix = try await oneShotLocation()
            let response = try await APIClient.shared.triggerAlert(
                latitude: fix.coordinate.latitude,
                longitude: fix.coordinate.longitude)
            activeAlertID = response.id
            trackingURL = response.trackingURL
            LocationStreamer.shared.start(alertID: response.id)
        } catch {
            lastError = "SOS could not be sent. Check your connection and try again."
        }
    }

    /// False alarm: Face ID/Touch ID gate lives in the view, this just
    /// tells the backend and stops location streaming.
    func cancelAsFalseAlarm() async {
        guard let id = activeAlertID else { return }
        try? await APIClient.shared.cancelAlert(alertID: id)
        LocationStreamer.shared.stop()
        activeAlertID = nil
        trackingURL = nil
    }

    /// One quick GPS fix for the initial alert. Not a stream.
    private func oneShotLocation() async throws -> CLLocation {
        let manager = CLLocationManager()
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        return try await withCheckedThrowingContinuation { continuation in
            let delegate = OneShotDelegate { continuation.resume(with: $0) }
            manager.delegate = delegate
            manager.desiredAccuracy = kCLLocationAccuracyBest
            manager.requestLocation()
            // Keep the delegate alive until the callback fires.
            objc_setAssociatedObject(manager, "delegate", delegate, .OBJC_ASSOCIATION_RETAIN)
        }
    }
}

/// Tiny delegate that resolves a single location request.
private final class OneShotDelegate: NSObject, CLLocationManagerDelegate {
    private let completion: (Result<CLLocation, Error>) -> Void
    private var done = false

    init(completion: @escaping (Result<CLLocation, Error>) -> Void) {
        self.completion = completion
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !done, let fix = locations.last else { return }
        done = true
        completion(.success(fix))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard !done else { return }
        done = true
        completion(.failure(error))
    }
}
