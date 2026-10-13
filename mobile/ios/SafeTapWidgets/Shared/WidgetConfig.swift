import Foundation
import Security

/// Backend constants for the widget extension. The app target has its own
/// AppConfig; this file keeps the extension independent so it never imports
/// the app target.
enum WidgetConfig {
    static let baseURL = URL(string: "http://localhost:8000")!
}

/// Reads the bearer token saved by the app. Both targets must share a
/// Keychain access group (Keychain Sharing capability) for this to work.
/// If the user is not logged in, the widget cannot fire an SOS.
enum TokenBridge {
    private static let service = "com.safetap.app"
    private static let account = "authToken"
    // Set this to your real access group, e.g. "AB12CD34EF.com.safetap.shared".
    private static let accessGroup = "com.safetap.shared"

    static func loadToken() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
