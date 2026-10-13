import SwiftUI

/// Shows the platform differences doc inside the app so users know exactly
/// what their phone can and cannot do.
struct AboutView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Group {
                        Text("Your SOS button").font(.headline)
                        Text("On iOS 17 and newer, the widget's SOS button fires the alert right from your Home Screen or Lock Screen, with a 5-second cancel window. On iOS 16, tapping the widget opens the app and asks for one confirming tap instead.")
                        Text("Text messages").font(.headline)
                        Text("iPhones cannot send texts silently in the background the way Android can. So when you trigger an SOS, our servers send the SMS to your contacts, and the tracking link goes out by WhatsApp and voice call too. If you share your own link, your Messages app opens with everything pre-filled and you just press send.")
                        Text("Location sharing").font(.headline)
                        Text("Your location is only shared while an SOS is active, once every 15 seconds. We never track you in the background at any other time.")
                        Text("Emergency numbers").font(.headline)
                        Text("For information only. SafeTap never dials these for you. National emergency: 112. Lagos rapid response: 767.")
                    }
                    .font(.body)
                    .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("How SafeTap works")
        }
    }
}
