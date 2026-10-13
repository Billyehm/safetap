import SwiftUI
import LocalAuthentication

/// False-alarm stand-down. Requires Face ID or Touch ID so a stranger holding
/// the phone cannot silently cancel a real emergency.
struct FalseAlarmView: View {
    @EnvironmentObject private var session: AlertSession
    @Environment(\.dismiss) private var dismiss
    @State private var status: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "faceid")
                    .font(.system(size: 64))
                    .foregroundStyle(.blue)

                Text("Confirm it's really you")
                    .font(.title2).bold()

                Text("Cancelling an SOS stops all contact alerts. We need Face ID or Touch ID to make sure this is your call.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                Button {
                    authenticate()
                } label: {
                    Text(working ? "Checking..." : "Verify and stand down")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(.blue)
                        .foregroundStyle(.white)
                        .clipShape(.capsule)
                }
                .padding(.horizontal)
                .disabled(working)

                if let status {
                    Text(status).foregroundStyle(.red).font(.footnote)
                }

                Spacer()
            }
            .padding(.top, 40)
            .navigationTitle("False alarm")
            .toolbar {
                Button("Close") { dismiss() }
            }
        }
    }

    private func authenticate() {
        let context = LAContext()
        var authError: NSError?

        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError) else {
            status = "This device has no Face ID or Touch ID set up."
            return
        }

        working = true
        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: "Confirm your identity to cancel the SOS"
        ) { success, _ in
            DispatchQueue.main.async {
                self.working = false
                if success {
                    Task {
                        await session.cancelAsFalseAlarm()
                        dismiss()
                    }
                } else {
                    status = "Verification failed. The SOS stays active."
                }
            }
        }
    }
}
