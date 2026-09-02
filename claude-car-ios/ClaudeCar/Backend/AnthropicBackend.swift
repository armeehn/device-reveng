import Foundation

/// api.anthropic.com directly. No SDK exists for Swift, so this is the raw
/// Messages API: one POST per turn with the whole transcript, SSE back.
///
/// Stateless by nature — the transcript lives in `Transcript` on this device,
/// and every turn replays it.
struct AnthropicBackend: ChatBackend {

    let apiKey: String
    let model: String

    static let defaultModel = "claude-opus-5"

    private static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!
    private static let modelsEndpoint = URL(string: "https://api.anthropic.com/v1/models")!
    private static let apiVersion = "2023-06-01"
    /// Server-side refusal fallback, `fallbacks: "default"` form — a policy
    /// decline reruns the turn on a fallback model inside the same call.
    private static let fallbackBeta = "server-side-fallback-2026-07-01"
    private static let httpOK = 200
    private static let ssePrefix = "data: "

    /// A car chat wants short, quick answers: effort low keeps adaptive
    /// thinking on but shallow, and the cap is a deliberate short-form ceiling.
    private static let effort = "low"
    private static let maxTokens = 8192
    private static let system = """
        You are Claude in a car. Keep answers short and conversational. \
        Plain prose unless the user asks for a list or code.
        """

    private static let streaming: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 600
        return URLSession(configuration: cfg)
    }()

    private let transcript = Transcript(name: "anthropic-transcript")

    // A private stored property makes the memberwise init private too.
    init(apiKey: String, model: String) {
        self.apiKey = apiKey
        self.model = model
    }

    // MARK: - request

    private func request(_ message: String, history: [Message]) -> URLRequest? {
        guard !apiKey.isEmpty else { return nil }

        var turns = history.compactMap { msg -> [String: String]? in
            guard msg.role != .error, !msg.text.isEmpty else { return nil }
            return ["role": msg.role == .user ? "user" : "assistant", "content": msg.text]
        }
        turns.append(["role": "user", "content": message])

        let body: [String: Any] = [
            "model": model,
            "max_tokens": Self.maxTokens,
            "stream": true,
            "system": Self.system,
            "output_config": ["effort": Self.effort],
            "fallbacks": "default",
            "messages": turns,
        ]

        var req = URLRequest(url: Self.endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        req.setValue(Self.apiVersion, forHTTPHeaderField: "anthropic-version")
        req.setValue(Self.fallbackBeta, forHTTPHeaderField: "anthropic-beta")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return req
    }

    // MARK: - stream

    /// The subset of the SSE event vocabulary this client acts on. Thinking
    /// deltas carry a different `delta.type` and fall through untouched.
    private struct Event: Decodable {
        struct Delta: Decodable {
            let type: String?
            let text: String?
            let stop_reason: String?
        }
        struct APIError: Decodable {
            let type: String?
            let message: String?
        }
        let type: String
        let delta: Delta?
        let error: APIError?
    }

    /// Non-2xx bodies are JSON with the reason inside.
    private struct ErrorBody: Decodable {
        struct Inner: Decodable {
            let message: String?
        }
        let error: Inner?
    }

    func send(_ message: String, history: [Message]) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                guard let req = request(message, history: history) else {
                    continuation.yield(.error("no Anthropic API key set"))
                    continuation.finish()
                    return
                }
                do {
                    let (bytes, response) = try await Self.streaming.bytes(for: req)
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard code == Self.httpOK else {
                        var raw = Data()
                        for try await byte in bytes { raw.append(byte) }
                        let reason = (try? JSONDecoder().decode(ErrorBody.self, from: raw))?.error?.message
                        continuation.yield(.error("Anthropic HTTP \(code): \(reason ?? "no detail")"))
                        continuation.finish()
                        return
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix(Self.ssePrefix),
                              let data = String(line.dropFirst(Self.ssePrefix.count)).data(using: .utf8),
                              let evt = try? JSONDecoder().decode(Event.self, from: data)
                        else { continue }

                        switch evt.type {
                        case "content_block_delta":
                            if evt.delta?.type == "text_delta", let text = evt.delta?.text {
                                continuation.yield(.text(text))
                            }
                        case "message_delta":
                            if evt.delta?.stop_reason == "refusal" {
                                continuation.yield(.error("Claude declined to answer that."))
                                continuation.finish()
                                return
                            }
                        case "message_stop":
                            continuation.yield(.done)
                            continuation.finish()
                            return
                        case "error":
                            continuation.yield(.error(evt.error?.message ?? "stream error"))
                            continuation.finish()
                            return
                        default:
                            continue
                        }
                    }
                } catch {
                    if !Task.isCancelled {
                        continuation.yield(.error(error.localizedDescription))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - conversation

    func conversation() async -> [Message] {
        transcript.load()
    }

    func startNewConversation() async {
        transcript.clear()
    }

    func persist(_ messages: [Message]) async {
        transcript.save(messages)
    }

    /// GET /v1/models: proves key and network without spending tokens.
    func health() async -> Bool {
        guard !apiKey.isEmpty else { return false }
        var req = URLRequest(url: Self.modelsEndpoint)
        req.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        req.setValue(Self.apiVersion, forHTTPHeaderField: "anthropic-version")
        req.timeoutInterval = 8
        guard let response = try? await URLSession.shared.data(for: req).1 else { return false }
        return (response as? HTTPURLResponse)?.statusCode == Self.httpOK
    }
}
