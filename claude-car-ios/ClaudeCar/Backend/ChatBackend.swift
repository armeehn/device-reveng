import Foundation

/// One event from a backend, whatever it is underneath.
enum ChatEvent {
    /// Appended to the turn verbatim. Backends that deliver whole blocks rather
    /// than token deltas emit their own separators.
    case text(String)
    case tool(String)
    case error(String)
    case done
}

/// Which Claude a turn goes to.
enum BackendKind: String, CaseIterable, Identifiable {
    /// A claude-car server (server.py), which owns the conversation and can use
    /// tools on the host it runs on.
    case claudeCar
    /// api.anthropic.com directly, with a key on this device. Stateless: the app
    /// keeps the transcript and replays it every turn.
    case anthropic

    var id: String { rawValue }

    var label: String {
        switch self {
        case .claudeCar: return "claude-car server"
        case .anthropic: return "Anthropic API"
        }
    }
}

protocol ChatBackend: Sendable {

    /// Streams one turn. `history` is what the screen already shows; a backend
    /// that keeps its own conversation ignores it.
    func send(_ message: String, history: [Message]) -> AsyncStream<ChatEvent>

    /// Whatever transcript this backend can replay, oldest first.
    func conversation() async -> [Message]

    /// Drop the current conversation and start a fresh one.
    func startNewConversation() async

    /// Reachable and usable right now.
    func health() async -> Bool

    /// Hand the backend the transcript after a turn. Only stateless backends
    /// need it; the default does nothing.
    func persist(_ messages: [Message]) async
}

/// A turn collected for speech: what Claude said, and whether the turn
/// actually finished inside the deadline.
struct SpokenAnswer {
    let text: String
    let complete: Bool
    let failure: String?
}

extension ChatBackend {

    func persist(_ messages: [Message]) async {}

    /// Run a turn and stop listening after `deadline`. Siri hands an intent a
    /// short, undocumented budget, so a turn that runs long is cut off here and
    /// spoken as far as it got. With a claude-car backend the turn keeps running
    /// server-side and shows up in the app; with a stateless one it is lost.
    func answer(_ message: String, history: [Message],
                within deadline: TimeInterval) async -> SpokenAnswer {
        let work = Task { () -> SpokenAnswer in
            var text = ""
            for await event in send(message, history: history) {
                switch event {
                case .text(let chunk):
                    text += chunk
                case .tool:
                    continue
                case .error(let reason):
                    return SpokenAnswer(text: text, complete: true, failure: reason)
                case .done:
                    return SpokenAnswer(text: text, complete: true, failure: nil)
                }
            }
            // Fell out of the loop: the deadline cancelled it mid-turn.
            return SpokenAnswer(text: text, complete: false, failure: nil)
        }
        let timer = Task {
            try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
            work.cancel()
        }
        let answer = await work.value
        timer.cancel()
        return answer
    }
}

/// Builds the backend the user has selected. Everything that talks to Claude
/// goes through here, so the app, the Siri intent and any future surface all
/// agree on which one is in use.
enum Backends {

    static func current() -> ChatBackend {
        switch ClientConfig.backend {
        case .claudeCar:
            return ClaudeCarBackend(server: ClientConfig.server, clientID: ClientConfig.clientID)
        case .anthropic:
            return AnthropicBackend(apiKey: ClientConfig.apiKey, model: ClientConfig.model)
        }
    }
}
