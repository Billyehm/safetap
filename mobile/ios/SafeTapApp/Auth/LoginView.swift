import SwiftUI

/// Login screen: phone number plus PIN, token stored in the Keychain on success.
struct LoginView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var phone = ""
    @State private var pin = ""
    @State private var isBusy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "shield.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.red)

                Text("SafeTap")
                    .font(.largeTitle).bold()

                TextField("Phone number", text: $phone)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .textFieldStyle(.roundedBorder)

                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)

                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }

                Button {
                    Task { await logIn() }
                } label: {
                    Text(isBusy ? "Signing in..." : "Sign in")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(.red)
                        .foregroundStyle(.white)
                        .clipShape(.capsule)
                }
                .disabled(isBusy || phone.isEmpty || pin.isEmpty)

                Spacer()
            }
            .padding()
            .navigationTitle("Welcome")
        }
    }

    private func logIn() async {
        isBusy = true
        error = nil
        defer { isBusy = false }
        do {
            let token = try await APIClient.shared.login(phone: phone, pin: pin)
            KeychainStore.shared.saveToken(token)
            session.isLoggedIn = true
        } catch {
            self.error = "Could not sign in. Check your phone number and PIN."
        }
    }
}

/// Simple session flag the root view observes.
final class SessionStore: ObservableObject {
    @Published var isLoggedIn: Bool = KeychainStore.shared.loadToken() != nil
}
