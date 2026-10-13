import SwiftUI

/// App root. Logged-out users see LoginView, everyone else gets the tabs.
/// Handles the `safetap://sos` deep link from the iOS 16 widget fallback.
struct ContentView: View {
    @EnvironmentObject private var auth: SessionStore
    @EnvironmentObject private var alerts: AlertSession
    @State private var showingSosConfirm = false

    var body: some View {
        Group {
            if auth.isLoggedIn {
                if alerts.isAlertActive {
                    ActiveAlertView()
                } else {
                    TabView {
                        ContactListView()
                            .tabItem { Label("Contacts", systemImage: "person.2.fill") }
                        MedicalProfileView()
                            .tabItem { Label("Medical", systemImage: "cross.fill") }
                        CheckInTimerView()
                            .tabItem { Label("Check-in", systemImage: "timer") }
                        AboutView()
                            .tabItem { Label("About", systemImage: "info.circle") }
                    }
                }
            } else {
                LoginView()
            }
        }
        .sheet(isPresented: $showingSosConfirm) {
            SosConfirmationView()
        }
        .task {
            // Pick up an SOS the widget fired while the app was closed.
            alerts.adoptWidgetAlertIfNeeded()
        }
        .onOpenURL { url in
            // iOS 16 fallback: widget tap opens the app, we land on confirmation.
            if url.scheme == AppConfig.urlScheme, url.host == "sos" {
                showingSosConfirm = true
            }
        }
    }
}
