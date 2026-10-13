import SwiftUI

/// Medical profile shown to responders through the tracking link.
/// Keep it short and accurate, this is what a paramedic reads first.
struct MedicalProfileView: View {
    @State private var profile = MedicalProfile()
    @State private var saved = false
    @State private var error: String?

    private let bloodTypes = ["", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Basics") {
                    Picker("Blood type", selection: $profile.bloodType) {
                        ForEach(bloodTypes, id: \.self) { Text($0.isEmpty ? "Not set" : $0) }
                    }
                    TextField("Doctor's phone", text: $profile.doctorPhone)
                        .keyboardType(.phonePad)
                }
                Section("Conditions and allergies") {
                    TextField("Allergies", text: $profile.allergies)
                    TextField("Conditions (e.g. asthma, diabetes)", text: $profile.conditions)
                    TextField("Medications", text: $profile.medications)
                }
                Section("Anything else responders should know") {
                    TextEditor(text: $profile.note)
                        .frame(minHeight: 80)
                }
                if saved {
                    Text("Saved").foregroundStyle(.green)
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
            .navigationTitle("Medical profile")
            .toolbar {
                Button("Save") {
                    Task {
                        do {
                            try await APIClient.shared.saveMedicalProfile(profile)
                            saved = true
                            error = nil
                        } catch {
                            error = "Could not save. Try again."
                            saved = false
                        }
                    }
                }
            }
            .task {
                do { profile = try await APIClient.shared.fetchMedicalProfile() }
                catch { /* keep the blank form, user can fill and save */ }
            }
        }
    }
}
