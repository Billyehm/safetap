import Foundation
import CoreLocation

// MARK: - Models matching the backend contract

struct EmergencyContact: Codable, Identifiable {
    var id: String?
    var name: String
    var phone: String
}

struct MedicalProfile: Codable {
    var bloodType: String = ""
    var allergies: String = ""
    var conditions: String = ""
    var medications: String = ""
    var doctorPhone: String = ""
    var note: String = ""
}

struct CheckInTimer: Codable {
    var enabled: Bool = false
    /// Deadline in "HH:mm" 24-hour format, e.g. "22:30"
    var deadline: String = "22:30"
    var message: String = ""
}

struct AlertResponse: Codable {
    var id: String
    var trackingURL: String
}

// MARK: - HTTP client

/// One shared client for every SafeTap backend call.
/// Uses the bearer token from the Keychain on each request.
final class APIClient {
    static let shared = APIClient()
    private init() {}

    private func request(_ path: String, method: String = "GET", body: Data? = nil) -> URLRequest {
        var req = URLRequest(url: AppConfig.baseURL.appendingPathComponent(path))
        req.httpMethod = method
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = KeychainStore.shared.loadToken() {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return req
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try JSONDecoder().decode(type, from: data)
    }

    // MARK: Auth

    func login(phone: String, pin: String) async throws -> String {
        let body = try JSONEncoder().encode(["phone": phone, "pin": pin])
        let (data, _) = try await URLSession.shared.data(for: request("api/auth/login", method: "POST", body: body))
        struct TokenResponse: Decodable { var token: String }
        return try decode(TokenResponse.self, from: data).token
    }

    // MARK: Contacts

    func fetchContacts() async throws -> [EmergencyContact] {
        let (data, _) = try await URLSession.shared.data(for: request("api/contacts"))
        return try decode([EmergencyContact].self, from: data)
    }

    func addContact(_ contact: EmergencyContact) async throws {
        let body = try JSONEncoder().encode(contact)
        _ = try await URLSession.shared.data(for: request("api/contacts", method: "POST", body: body))
    }

    func deleteContact(id: String) async throws {
        _ = try await URLSession.shared.data(for: request("api/contacts/\(id)", method: "DELETE"))
    }

    // MARK: Medical profile

    func fetchMedicalProfile() async throws -> MedicalProfile {
        let (data, _) = try await URLSession.shared.data(for: request("api/medical"))
        return try decode(MedicalProfile.self, from: data)
    }

    func saveMedicalProfile(_ profile: MedicalProfile) async throws {
        let body = try JSONEncoder().encode(profile)
        _ = try await URLSession.shared.data(for: request("api/medical", method: "PUT", body: body))
    }

    // MARK: Check-in timer

    func fetchCheckIn() async throws -> CheckInTimer {
        let (data, _) = try await URLSession.shared.data(for: request("api/checkin"))
        return try decode(CheckInTimer.self, from: data)
    }

    func saveCheckIn(_ timer: CheckInTimer) async throws {
        let body = try JSONEncoder().encode(timer)
        _ = try await URLSession.shared.data(for: request("api/checkin", method: "PUT", body: body))
    }

    // MARK: Alerts

    /// Fires the SOS. Returns the alert id and the no-login tracking URL.
    func triggerAlert(latitude: Double, longitude: Double) async throws -> AlertResponse {
        let body = try JSONEncoder().encode(["lat": latitude, "lon": longitude])
        let (data, _) = try await URLSession.shared.data(for: request("api/alerts", method: "POST", body: body))
        return try decode(AlertResponse.self, from: data)
    }

    /// Pushes one GPS fix for an active alert.
    func pushLocation(alertID: String, latitude: Double, longitude: Double) async throws {
        let body = try JSONEncoder().encode(["lat": latitude, "lon": longitude])
        _ = try await URLSession.shared.data(
            for: request("api/alerts/\(alertID)/location", method: "POST", body: body))
    }

    /// Marks an alert as a false alarm. Backend stops contacting people.
    func cancelAlert(alertID: String) async throws {
        _ = try await URLSession.shared.data(
            for: request("api/alerts/\(alertID)/false-alarm", method: "POST"))
    }
}
