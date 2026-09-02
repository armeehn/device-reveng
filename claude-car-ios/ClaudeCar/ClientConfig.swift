import Foundation

/// Everything the app and the Siri intent must agree on: which backend, how to
/// reach it, and which client id. Both run in the same process, so plain
/// UserDefaults is enough — no app group needed. The API key is the exception
/// and lives in the keychain.
///
/// Nothing is baked in: a fresh install asks for the backend on first launch.
enum ClientConfig {

    private static let clientKey = "client_id"
    private static let serverKey = "server_url"
    private static let backendKey = "backend"
    private static let modelKey = "anthropic_model"
    private static let apiKeyAccount = "anthropic-api-key"

    /// Stable per-install id. A claude-car server keys its persistent Claude
    /// session on it, so a question asked through Siri and one typed on screen
    /// land in the same conversation.
    static var clientID: String {
        if let saved = UserDefaults.standard.string(forKey: clientKey) {
            return saved
        }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: clientKey)
        return fresh
    }

    static var backend: BackendKind {
        get {
            let raw = UserDefaults.standard.string(forKey: backendKey) ?? ""
            return BackendKind(rawValue: raw) ?? .claudeCar
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: backendKey) }
    }

    static var server: String {
        get { UserDefaults.standard.string(forKey: serverKey) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: serverKey) }
    }

    static var model: String {
        get { UserDefaults.standard.string(forKey: modelKey) ?? AnthropicBackend.defaultModel }
        set { UserDefaults.standard.set(newValue, forKey: modelKey) }
    }

    static var apiKey: String {
        get { Keychain.read(apiKeyAccount) ?? "" }
        set { Keychain.write(newValue, account: apiKeyAccount) }
    }

    /// The selected backend has what it needs to be reached at all.
    static var isConfigured: Bool {
        switch backend {
        case .claudeCar: return !server.isEmpty
        case .anthropic: return !apiKey.isEmpty
        }
    }
}
