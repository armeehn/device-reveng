import Foundation

/// On-disk transcript for stateless backends. The Anthropic API keeps nothing,
/// so the conversation only exists if the app writes it down — and the Siri
/// intent reads it back to give the next turn its history.
struct Transcript {

    private let file: URL

    init(name: String) {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent("\(name).json")
    }

    func load() -> [Message] {
        guard let data = try? Data(contentsOf: file),
              let stored = try? JSONDecoder().decode([Stored].self, from: data)
        else { return [] }
        return stored.map { Message(role: $0.role == "user" ? .user : .assistant, text: $0.text) }
    }

    /// Errors are screen furniture, not conversation — they never go to the model.
    func save(_ messages: [Message]) {
        let stored = messages.compactMap { msg -> Stored? in
            guard msg.role != .error, !msg.text.isEmpty else { return nil }
            return Stored(role: msg.role == .user ? "user" : "assistant", text: msg.text)
        }
        guard let data = try? JSONEncoder().encode(stored) else { return }
        try? data.write(to: file, options: .atomic)
    }

    func clear() {
        try? FileManager.default.removeItem(at: file)
    }

    private struct Stored: Codable {
        let role: String
        let text: String
    }
}
