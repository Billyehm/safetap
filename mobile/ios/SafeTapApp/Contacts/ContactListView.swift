import SwiftUI

/// Manages emergency contacts. Free tier allows at most two.
struct ContactListView: View {
    @State private var contacts: [EmergencyContact] = []
    @State private var showingAdd = false
    @State private var name = ""
    @State private var phone = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(contacts) { contact in
                    VStack(alignment: .leading) {
                        Text(contact.name).font(.headline)
                        Text(contact.phone).foregroundStyle(.secondary)
                    }
                }
                .onDelete(perform: delete)
            }
            .navigationTitle("Emergency contacts")
            .toolbar {
                Button {
                    if contacts.count >= AppConfig.freeTierContactLimit {
                        error = "Free plan allows up to \(AppConfig.freeTierContactLimit) contacts."
                    } else {
                        showingAdd = true
                    }
                } label: { Image(systemName: "plus") }
            }
            .alert("Error", isPresented: .constant(error != nil)) {
                Button("OK") { error = nil }
            } message: {
                Text(error ?? "")
            }
            .sheet(isPresented: $showingAdd) {
                NavigationStack {
                    Form {
                        TextField("Name", text: $name)
                        TextField("Phone", text: $phone)
                            .keyboardType(.phonePad)
                    }
                    .navigationTitle("New contact")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { showingAdd = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") {
                                Task {
                                    do {
                                        try await APIClient.shared.addContact(
                                            EmergencyContact(id: nil, name: name, phone: phone))
                                        name = ""; phone = ""
                                        showingAdd = false
                                        await load()
                                    } catch {
                                        error = "Could not save the contact."
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        do { contacts = try await APIClient.shared.fetchContacts() }
        catch { error = "Could not load contacts." }
    }

    private func delete(at offsets: IndexSet) {
        Task {
            for i in offsets {
                guard let id = contacts[i].id else { continue }
                try? await APIClient.shared.deleteContact(id: id)
            }
            await load()
        }
    }
}
