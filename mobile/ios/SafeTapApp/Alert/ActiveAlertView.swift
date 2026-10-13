import SwiftUI

/// Shown while an SOS is live: streaming indicator plus the false-alarm exit.
struct ActiveAlertView: View {
    @EnvironmentObject private var session: AlertSession
    @ObservedObject private var streamer = LocationStreamer.shared
    @State private var showingFalseAlarm = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Spacer()

                Image(systemName: "location.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.red)
                    .symbolEffect(.pulse)

                Text("SOS ACTIVE")
                    .font(.largeTitle).bold()
                    .foregroundStyle(.red)

                if streamer.isStreaming {
                    Label("Sharing live location with your contacts", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let url = session.trackingURL, let link = URL(string: url) {
                    ShareLink(item: link) {
                        Label("Share my tracking link", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(.borderedProminent)
                }

                Spacer()

                Button {
                    showingFalseAlarm = true
                } label: {
                    Text("False alarm, stand down")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(.gray.opacity(0.2))
                        .foregroundStyle(.primary)
                        .clipShape(.capsule)
                }
                .padding(.horizontal)
                .padding(.bottom)
            }
            .navigationTitle("Emergency")
            .sheet(isPresented: $showingFalseAlarm) { FalseAlarmView() }
        }
    }
}
