import Foundation
import CoreLocation

/// Streams GPS fixes to the backend only while an SOS alert is active.
/// Key rule: no passive background location. We ask for location permission
/// when the alert fires, push every 15 seconds, and stop the moment the
/// alert ends (cancelled, false alarm, or resolved).
///
/// iOS note: this uses `requestLocation()` style foreground updates driven by
/// a timer. We deliberately do NOT set `allowsBackgroundLocationUpdates` and
/// we do not add the "location" background mode, so the OS cannot track the
/// user outside an active emergency.
final class LocationStreamer: NSObject, ObservableObject {
    static let shared = LocationStreamer()
    private override init() { super.init() }

    private let manager = CLLocationManager()
    private var timer: Timer?
    private var alertID: String?
    private var lastFix: CLLocation?

    @Published private(set) var isStreaming = false

    // MARK: Lifecycle

    /// Called right after the backend acknowledges the SOS.
    func start(alertID: String) {
        self.alertID = alertID
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest

        let status = manager.authorizationStatus
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }

        // Immediate first fix, then one every 15 seconds.
        manager.requestLocation()
        timer = Timer.scheduledTimer(withTimeInterval: AppConfig.locationPushInterval, repeats: true) { [weak self] _ in
            self?.manager.requestLocation()
        }
        isStreaming = true
    }

    /// Stops all location work. Call on cancel, false alarm, or resolution.
    func stop() {
        timer?.invalidate()
        timer = nil
        alertID = nil
        lastFix = nil
        isStreaming = false
    }

    private func push(_ location: CLLocation) {
        guard let alertID else { return }
        lastFix = location
        Task {
            try? await APIClient.shared.pushLocation(
                alertID: alertID,
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude)
        }
    }
}

extension LocationStreamer: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.last else { return }
        // Skip stale cached fixes so the first push is a real position.
        guard abs(fix.timestamp.timeIntervalSinceNow) < 30 else { return }
        push(fix)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // One missed fix is fine, the next timer tick retries.
        // We never retry in a loop here, to avoid draining the battery.
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // If the user denies location during an alert, the backend still has
        // the position captured at trigger time. Nothing else to do.
    }
}
