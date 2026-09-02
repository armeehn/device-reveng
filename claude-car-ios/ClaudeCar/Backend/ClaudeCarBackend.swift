import Foundation

/// The self-hosted backend: server.py owns the conversation, runs Claude Code
/// with tools, and streams SSE. See ../Net/ClaudeAPI.swift for the transport.
struct ClaudeCarBackend: ChatBackend {

    let server: String
    let clientID: String

    /// The server sends whole text blocks, not token deltas, so the paragraph
    /// break between them is put back here rather than in the view model.
    func send(_ message: String, history: [Message]) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                var seenText = false
                for await event in ClaudeAPI.chat(server: server, clientID: clientID, message: message) {
                    if case .text(let block) = event {
                        continuation.yield(.text(seenText ? "\n\n" + block : block))
                        seenText = true
                        continue
                    }
                    continuation.yield(event)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func conversation() async -> [Message] {
        await ClaudeAPI.history(server: server, clientID: clientID).map {
            Message(role: $0.role == "user" ? .user : .assistant, text: $0.text)
        }
    }

    func startNewConversation() async {
        await ClaudeAPI.newConversation(server: server, clientID: clientID)
    }

    func health() async -> Bool {
        await ClaudeAPI.health(server: server)
    }
}
