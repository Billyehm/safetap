import SwiftUI
import MessageUI

/// iOS limitation: unlike Android, an iPhone app cannot send an SMS silently
/// in the background. There is no API for it, and Apple rejects apps that
/// try to work around it.
///
/// So SafeTap handles texts two ways:
///
/// 1. Backend-driven SMS (the real path). When you trigger an SOS, our
///    servers text your contacts directly. The app itself sends nothing.
///    This is what reaches people who do not have the app.
///
/// 2. Pre-filled composer (the manual fallback). If the backend SMS fails or
///    you want to send something yourself, this opens the Messages app with
///    the recipients and message already filled in. You just press send.
///    One extra tap, but it always works.
struct SmsComposer: UIViewControllerRepresentable {
    var recipients: [String]
    var body: String
    var onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let composer = MFMessageComposeViewController()
        composer.recipients = recipients
        composer.body = body
        composer.messageComposeDelegate = context.coordinator
        return composer
    }

    func updateUIViewController(_ uiViewController: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }

        func messageComposeViewController(
            _ controller: MFMessageComposeViewController,
            didFinishWith result: MessageComposeResult
        ) {
            controller.dismiss(animated: true)
            onDone()
        }
    }
}

/// Convenience: builds the SOS text from an active alert.
enum SosMessage {
    static func text(trackingURL: String?) -> String {
        var msg = "EMERGENCY: I need help. This is an automated SOS from SafeTap."
        if let trackingURL {
            msg += " Track my live location here: \(trackingURL)"
        }
        msg += " If you cannot reach me, please call emergency services."
        return msg
    }

    static func canSend() -> Bool {
        MFMessageComposeViewController.canSendText()
    }
}
