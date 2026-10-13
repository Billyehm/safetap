import SwiftUI

/// Full-screen SOS confirmation. This is the second step of the iOS 16
/// fallback flow: the user taps the widget, iOS opens the app through
/// `safetap://sos`, and this view asks for one final deliberate tap before
/// the SOS goes out. No accidental sends from a pocket tap on the widget.
struct SosConfirmationView: View {
    @EnvironmentObject private var session: AlertSession
    @Environment(\.dismiss) private var dismiss
    @State private var countdown: Int?
    @State private var sending = false

    var body: some View {
        ZStack {
            Color.red.opacity(0.06).ignoresSafeArea()

            VStack(spacing: 24) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 72))
                    .foregroundStyle(.red)

                Text("Send emergency SOS?")
                    .font(.largeTitle).bold()

                Text("This alerts your emergency contacts by SMS, call and WhatsApp, and shares your live location.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)

                if let countdown {
                    Text("Sending in \(countdown)...")
                        .font(.title2).bold()
                        .foregroundStyle(.red)
                    Button("Cancel") { stopCountdown() }
                        .buttonStyle(.bordered)
                } else {
                    Button {
                        startCountdown()
                    } label: {
                        Text("YES, SEND SOS")
                            .font(.title2).bold()
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(.red)
                            .foregroundStyle(.white)
                            .clipShape(.capsule)
                    }
                    .padding(.horizontal)

                    Button("Not now") { dismiss() }
                        .foregroundStyle(.secondary)
                }

                if sending { ProgressView("Sending...") }

                if let error = session.lastError {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
            .padding()
        }
    }

    // MARK: 5-second cancellable countdown

    private func startCountdown() {
        countdown = 5
        tick()
    }

    private func tick() {
        guard let count = countdown, count > 0 else {
            countdown = nil
            send()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            guard self.countdown != nil else { return } // cancelled meanwhile
            self.countdown = count - 1
            self.tick()
        }
    }

    private func stopCountdown() {
        countdown = nil
    }

    private func send() {
        sending = true
        Task {
            await session.triggerSOS()
            sending = false
            if session.isAlertActive { dismiss() }
        }
    }
}
