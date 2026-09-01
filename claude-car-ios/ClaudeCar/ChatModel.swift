import Combine
import Foundation
import SwiftUI

enum Role {
    case user
    case assistant
    case error
}

struct Message: Identifiable {
    let id = UUID()
    let role: Role
    var text: String
    /// Names of tools Claude used while producing this message.
    var tools: [String] = []
}

@MainActor
final class ChatModel: ObservableObject {

    @Published private(set) var messages: [Message] = []
    @Published private(set) var streaming = false
    @Published private(set) var backendKind = ClientConfig.backend

    /// nil = unknown/checking, true/false = last health result.
    @Published private(set) var online: Bool?

    private var backend = Backends.current()

    func start() {
        guard ClientConfig.isConfigured else { return }
        checkHealth()
        refresh()
    }

    func checkHealth() {
        online = nil
        let backend = backend
        Task { online = await backend.health() }
    }

    /// Replays whatever the backend knows. For claude-car that is the server's
    /// transcript, and how turns asked through Siri show up here. Idle only: a
    /// live stream owns the transcript.
    func refresh() {
        guard !streaming else { return }
        let backend = backend
        Task {
            let stored = await backend.conversation()
            guard !streaming, !stored.isEmpty else { return }
            messages = stored
        }
    }

    /// Settings changed: rebuild the backend and show its conversation.
    func reconfigure() {
        backend = Backends.current()
        backendKind = ClientConfig.backend
        messages.removeAll()
        checkHealth()
        refresh()
    }

    func send(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !streaming else { return }

        streaming = true
        let history = messages
        messages.append(Message(role: .user, text: text))
        let turn = Message(role: .assistant, text: "")
        messages.append(turn)

        let backend = backend
        Task {
            for await event in backend.send(text, history: history) {
                switch event {
                case .text(let chunk):
                    update(turn.id) { $0.text += chunk }
                case .tool(let name):
                    update(turn.id) { $0.tools.append(name) }
                case .error(let text):
                    messages.append(Message(role: .error, text: text))
                case .done:
                    online = true
                }
            }
            // Drop the placeholder if the turn produced neither text nor tool use.
            if let i = index(of: turn.id), messages[i].text.isEmpty, messages[i].tools.isEmpty {
                messages.remove(at: i)
            }
            streaming = false
            await backend.persist(messages)
        }
    }

    func newChat() {
        guard !streaming else { return }
        let backend = backend
        Task {
            await backend.startNewConversation()
            messages.removeAll()
        }
    }

    private func index(of id: UUID) -> Int? {
        messages.firstIndex { $0.id == id }
    }

    private func update(_ id: UUID, _ change: (inout Message) -> Void) {
        guard let i = index(of: id) else { return }
        change(&messages[i])
    }
}
