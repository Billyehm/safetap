import WidgetKit
import SwiftUI

// MARK: - Timeline

struct SosEntry: TimelineEntry {
    let date: Date
    let phase: WidgetSharedState.Phase
    let countdownRemaining: Int
}

struct SosProvider: TimelineProvider {
    func placeholder(in context: Context) -> SosEntry {
        SosEntry(date: Date(), phase: .idle, countdownRemaining: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping (SosEntry) -> Void) {
        completion(currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SosEntry>) -> Void) {
        let entry = currentEntry()
        // During the 5-second countdown we refresh every second so the
        // number on screen ticks down live. Otherwise we stay quiet until
        // the next phase change reloads us.
        let policy: TimelineReloadPolicy
        if entry.phase == .countdown, entry.countdownRemaining > 0 {
            policy = .after(Date().addingTimeInterval(1))
        } else {
            policy = .never
        }
        completion(Timeline(entries: [entry], policy: policy))
    }

    private func currentEntry() -> SosEntry {
        SosEntry(
            date: Date(),
            phase: WidgetSharedState.phase,
            countdownRemaining: WidgetSharedState.countdownRemaining
        )
    }
}

// MARK: - Views

/// Home Screen widget (systemSmall): one large red SOS button.
/// On iOS 17+ the button runs SosIntent directly. On iOS 16 it deep-links
/// into the app (`safetap://sos`) and SosConfirmationView takes over.
struct SosWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SosEntry

    var body: some View {
        switch family {
        case .accessoryRectangular:
            LockScreenView(entry: entry)
        case .systemMedium:
            // Wide (roughly 4x2) layout: big SOS button left, label right.
            WideHomeScreenView(entry: entry)
        default:
            HomeScreenView(entry: entry)
        }
    }
}

private struct HomeScreenView: View {
    let entry: SosEntry

    var body: some View {
        ZStack {
            Color.red
            VStack(spacing: 6) {
                switch entry.phase {
                case .idle:
                    sosButton(label: "SOS", font: .largeTitle)
                    Text("Tap for emergency")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                case .arming:
                    Text("ARMING...")
                        .font(.headline).bold()
                        .foregroundStyle(.white)
                        .symbolEffect(.pulse)
                    cancelButton()
                case .countdown:
                    Text("Sending in \(entry.countdownRemaining)")
                        .font(.title2).bold()
                        .foregroundStyle(.white)
                    cancelButton()
                case .sent:
                    Image(systemName: "checkmark.circle.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.white)
                    Text("SOS sent")
                        .font(.caption).bold()
                        .foregroundStyle(.white)
                }
            }
        }
        // Note: no .containerBackground here on purpose. It needs iOS 17 and
        // the red ZStack above already paints the full widget on iOS 16 too.
    }

    @ViewBuilder
    private func sosButton(label: String, font: Font) -> some View {
        if #available(iOS 17, *) {
            Button(intent: SosIntent()) {
                Text(label)
                    .font(font).bold()
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(.plain)
        } else {
            // iOS 16 fallback: open the app, confirm there.
            Link(destination: URL(string: "safetap://sos")!) {
                Text(label)
                    .font(font).bold()
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    @ViewBuilder
    private func cancelButton() -> some View {
        if #available(iOS 17, *) {
            Button(intent: CancelSosIntent()) {
                Text("Cancel")
                    .font(.headline).bold()
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                    .background(.white)
                    .foregroundStyle(.red)
                    .clipShape(.capsule)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Wide Home Screen widget (systemMedium, roughly 4x2): big SOS button on
/// the left, label and hint on the right. iOS does not allow custom cell
/// sizes, so systemMedium is the closest to the Android 4x2 widget.
private struct WideHomeScreenView: View {
    let entry: SosEntry

    var body: some View {
        ZStack {
            Color.red
            HStack(spacing: 12) {
                if #available(iOS 17, *) {
                    Button(intent: SosIntent()) {
                        Text("SOS")
                            .font(.largeTitle).bold()
                            .foregroundStyle(.white)
                            .frame(width: 96, height: 96)
                            .background(.white.opacity(0.25))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                } else {
                    Link(destination: URL(string: "safetap://sos")!) {
                        Text("SOS")
                            .font(.largeTitle).bold()
                            .foregroundStyle(.white)
                            .frame(width: 96, height: 96)
                            .background(.white.opacity(0.25))
                            .clipShape(Circle())
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("SafeTap SOS")
                        .font(.headline).bold()
                        .foregroundStyle(.white)
                    Text("Tap for emergency")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                }
                Spacer()
            }
            .padding()
        }
    }
}

/// Lock Screen widget (accessoryRectangular): compact, monochrome-friendly.
/// Lock screen widgets are tinted by the system, so this stays simple.
private struct LockScreenView: View {
    let entry: SosEntry

    var body: some View {
        switch entry.phase {
        case .idle:
            if #available(iOS 17, *) {
                Button(intent: SosIntent()) {
                    Label("SOS", systemImage: "sos.circle.fill")
                        .font(.headline)
                }
                .buttonStyle(.plain)
            } else {
                Link(destination: URL(string: "safetap://sos")!) {
                    Label("SOS", systemImage: "sos.circle.fill")
                        .font(.headline)
                }
            }
        case .arming:
            Label("Arming...", systemImage: "exclamationmark.triangle.fill")
        case .countdown:
            Label("SOS in \(entry.countdownRemaining)s", systemImage: "timer")
        case .sent:
            Label("SOS sent", systemImage: "checkmark.circle.fill")
        }
    }
}

// MARK: - Widget definition

struct SosWidget: Widget {
    let kind = "SosWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: SosProvider()) { entry in
            SosWidgetView(entry: entry)
        }
        .configurationDisplayName("SafeTap SOS")
        .description("One-tap emergency SOS for your Home Screen and Lock Screen.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}

// MARK: - Bundle

@main
struct SafeTapWidgetsBundle: WidgetBundle {
    var body: some Widget {
        SosWidget()
    }
}
