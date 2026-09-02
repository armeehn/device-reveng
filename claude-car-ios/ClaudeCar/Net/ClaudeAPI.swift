import Foundation

/// A turn already persisted server-side, as returned by GET /history.
struct StoredMessage: Decodable {
    let role: String
    let text: String
}

/// Transport for the claude-car backend: POST /chat streams SSE `data:` lines,
/// POST /new drops the server-side conversation, GET /history replays the
/// current one, GET /health pings. Foundation only — no third-party deps.
enum ClaudeAPI {

    private static let httpOK = 200
    private static let ssePrefix = "data: "
    private static let shortTimeout: TimeInterval = 8

    /// A turn can legitimately run for minutes while Claude uses tools, and the
    /// request timeout is an inactivity timeout — so the streaming session gets
    /// its own generous one instead of the 60 s default.
    private static let streamTimeout: TimeInterval = 3600

    private static let short: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = shortTimeout
        return URLSession(configuration: cfg)
    }()

    private static let streaming: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = streamTimeout
        return URLSession(configuration: cfg)
    }()

    /// Accepts a bare `host:port` as well as a full URL — the settings sheet
    /// takes whatever the user types.
    static func url(_ server: String, _ path: String, query: [String: String] = [:]) -> URL? {
        var base = server.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else { return nil }
        if !base.lowercased().hasPrefix("http") {
            base = "http://" + base
        }
        while base.hasSuffix("/") {
            base.removeLast()
        }
        guard var comps = URLComponents(string: base + path) else { return nil }
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        return comps.url
    }

    private static func post(_ server: String, _ path: String, _ body: [String: String]) -> URLRequest? {
        guard let url = url(server, path) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return req
    }

    // MARK: - chat

    private struct Event: Decodable {
        let type: String
        let text: String?
        let name: String?
    }

    static func chat(server: String, clientID: String, message: String) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                guard let req = post(server, "/chat", ["message": message, "client": clientID]) else {
                    continuation.yield(.error("bad server URL: \(server.isEmpty ? "(not set)" : server)"))
                    continuation.finish()
                    return
                }
                do {
                    let (bytes, response) = try await streaming.bytes(for: req)
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard code == httpOK else {
                        continuation.yield(.error("server replied HTTP \(code)"))
                        continuation.finish()
                        return
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix(ssePrefix),
                              let data = String(line.dropFirst(ssePrefix.count)).data(using: .utf8),
                              let evt = try? JSONDecoder().decode(Event.self, from: data)
                        else { continue }

                        switch evt.type {
                        case "text":
                            continuation.yield(.text(evt.text ?? ""))
                        case "tool":
                            continuation.yield(.tool(evt.name ?? "tool"))
                        // The server keeps the socket alive after the turn, so EOF
                        // never comes — `done` IS end-of-turn. A bare `error` with
                        // no `done` (claude exited nonzero) is terminal too.
                        case "error":
                            continuation.yield(.error(evt.text ?? "error"))
                            continuation.finish()
                            return
                        case "done":
                            continuation.yield(.done)
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

    // MARK: - session

    /// POST /new — starts a fresh server-side conversation for this client.
    @discardableResult
    static func newConversation(server: String, clientID: String) async -> Bool {
        guard let req = post(server, "/new", ["client": clientID]) else { return false }
        guard let response = try? await short.data(for: req).1 else { return false }
        return (response as? HTTPURLResponse)?.statusCode == httpOK
    }

    private struct History: Decodable {
        let messages: [StoredMessage]
    }

    /// GET /history — the client's current conversation, so a relaunch shows the
    /// transcript the server has been keeping rather than an empty screen.
    static func history(server: String, clientID: String) async -> [StoredMessage] {
        guard let url = url(server, "/history", query: ["client": clientID]) else { return [] }
        guard let data = try? await short.data(from: url).0 else { return [] }
        return (try? JSONDecoder().decode(History.self, from: data))?.messages ?? []
    }

    static func health(server: String) async -> Bool {
        guard let url = url(server, "/health") else { return false }
        guard let response = try? await short.data(from: url).1 else { return false }
        return (response as? HTTPURLResponse)?.statusCode == httpOK
    }
}
