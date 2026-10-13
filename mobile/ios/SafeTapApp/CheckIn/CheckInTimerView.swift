import SwiftUI

/// Check-in timer: "alert my contacts if I don't check in by 22:30".
/// The deadline lives on the backend, which fires the alert if the user
/// never checks in. The app only displays and edits it.
struct CheckInTimerView: View {
    @State private var timer = CheckInTimer()
    @State private var deadlineDate = Date()
    @State private var saved = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Check-in timer on", isOn: $timer.enabled)
                } header: {
                    Text("How it works")
                } footer: {
                    Text("If you don't check in before the deadline, your contacts get an SOS with your last known location.")
                }
                Section("Deadline") {
                    DatePicker("Check in by", selection: $deadlineDate, displayedComponents: .hourAndMinute)
                        .disabled(!timer.enabled)
                    TextField("Message to contacts (optional)", text: $timer.message)
                        .disabled(!timer.enabled)
                }
                if saved { Text("Saved").foregroundStyle(.green) }
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
            }
            .navigationTitle("Check-in timer")
            .toolbar {
                Button("Save") {
                    Task {
                        var copy = timer
                        let parts = Calendar.current.dateComponents([.hour, .minute], from: deadlineDate)
                        copy.deadline = String(format: "%02d:%02d", parts.hour ?? 22, parts.minute ?? 30)
                        do {
                            try await APIClient.shared.saveCheckIn(copy)
                            saved = true
                            error = nil
                        } catch {
                            error = "Could not save the timer."
                            saved = false
                        }
                    }
                }
            }
            .task {
                do {
                    timer = try await APIClient.shared.fetchCheckIn()
                    let parts = timer.deadline.split(separator: ":").compactMap { Int($0) }
                    if parts.count == 2 {
                        var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
                        comps.hour = parts[0]; comps.minute = parts[1]
                        if let d = Calendar.current.date(from: comps) { deadlineDate = d }
                    }
                } catch { /* keep defaults */ }
            }
        }
    }
}
