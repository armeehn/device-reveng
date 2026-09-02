import SwiftUI

private let quickPrompts = [
    "What's the weather ahead?",
    "Summarize my day",
    "Find a coffee stop on my route",
    "Explain something interesting",
]

struct ChatView: View {

    @ObservedObject var model: ChatModel
    @StateObject private var mic = SpeechInput()

    @State private var draft = ""
    @State private var showSettings = false
    @State private var showMicDenied = false
    /// A fresh install has no backend: ask before showing an empty chat.
    @State private var needsSetup = !ClientConfig.isConfigured

    var body: some View {
        VStack(spacing: 0) {
            topBar
            transcript
            inputBar
        }
        .background(Color.ccInk.ignoresSafeArea())
        .sheet(isPresented: $showSettings) {
            SettingsSheet(mode: .edit) { model.reconfigure() }
        }
        .fullScreenCover(isPresented: $needsSetup) {
            SettingsSheet(mode: .setup) { model.reconfigure() }
        }
        // Live transcription lands in the draft; the user can still edit it.
        .onChange(of: mic.transcript) { _, text in
            if !text.isEmpty { draft = text }
        }
        .onChange(of: mic.denied) { _, denied in
            showMicDenied = denied
        }
        .alert("Microphone unavailable", isPresented: $showMicDenied) {
            Button("OK") {}
        } message: {
            Text("Allow the microphone and speech recognition for Claude in Settings.")
        }
    }

    // MARK: - top bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Text("✳").font(.title2).foregroundColor(.ccTerracotta)
            Text("Claude").font(.title3.bold()).foregroundColor(.ccBone)
            statusDot
            Spacer()
            Button("Settings") { showSettings = true }
            Button("New chat") { model.newChat() }
                .disabled(model.streaming)
        }
        .font(.subheadline)
        .tint(.ccTerracotta)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.ccPanel)
    }

    private var statusDot: some View {
        let (color, label): (Color, String) = {
            switch model.online {
            case .some(true): return (.ccOk, model.backendKind == .anthropic ? "API" : "online")
            case .some(false): return (.ccError, "offline")
            case nil: return (.ccDim, "checking…")
            }
        }()
        return HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption).foregroundColor(.ccDim)
        }
        .onTapGesture { model.checkHealth() }
    }

    // MARK: - transcript

    @ViewBuilder
    private var transcript: some View {
        if model.messages.isEmpty {
            emptyState
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(model.messages) { msg in
                            Bubble(message: msg, streaming: model.streaming && msg.id == model.messages.last?.id)
                                .id(msg.id)
                        }
                        // Zero-height anchor: scrolling to the last bubble stops
                        // at its top once it grows past a screenful.
                        Color.clear.frame(height: 1).id(bottomAnchor)
                    }
                    .padding(16)
                }
                .onChange(of: scrollKey) {
                    withAnimation { proxy.scrollTo(bottomAnchor, anchor: .bottom) }
                }
            }
        }
    }

    private var bottomAnchor: String { "bottom" }

    /// Any growth of the transcript, including the last message streaming in.
    private var scrollKey: String {
        let last = model.messages.last
        return "\(model.messages.count)-\(last?.text.count ?? 0)-\(last?.tools.count ?? 0)"
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("✳").font(.system(size: 44)).foregroundColor(.ccTerracotta)
            Text("Ask Claude anything").font(.title3).foregroundColor(.ccDim)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(quickPrompts, id: \.self) { prompt in
                        Button(prompt) { model.send(prompt) }
                            .buttonStyle(.bordered)
                            .tint(.ccTerracotta)
                            .disabled(model.streaming)
                    }
                }
                .padding(.horizontal, 16)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - input

    private var inputBar: some View {
        HStack(spacing: 12) {
            TextField(mic.listening ? "Listening…" : "Message Claude…", text: $draft, axis: .vertical)
                .lineLimit(1...4)
                .textFieldStyle(.plain)
                .foregroundColor(.ccBone)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.ccPanelHigh, in: RoundedRectangle(cornerRadius: 18))
                .submitLabel(.send)
                .onSubmit(submit)

            micButton

            Button(action: submit) {
                Text(model.streaming ? "Working…" : "Send").fontWeight(.semibold)
            }
            .buttonStyle(.borderedProminent)
            .tint(.ccTerracotta)
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.streaming)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.ccPanel)
    }

    /// Big enough to hit from the driver's seat; red while the mic is open.
    private var micButton: some View {
        Button { mic.toggle() } label: {
            Image(systemName: mic.listening ? "mic.fill" : "mic")
                .font(.title2)
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.bordered)
        .tint(mic.listening ? .ccError : .ccTerracotta)
        .disabled(model.streaming)
        .accessibilityLabel(mic.listening ? "Stop listening" : "Dictate")
    }

    private func submit() {
        guard !model.streaming else { return }
        mic.stop()
        model.send(draft)
        draft = ""
    }
}

// MARK: - bubbles

private struct Bubble: View {

    let message: Message
    let streaming: Bool

    var body: some View {
        HStack {
            if message.role == .user { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 8) {
                if !message.tools.isEmpty {
                    ToolChips(tools: message.tools)
                }
                content
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(bubbleColor, in: RoundedRectangle(cornerRadius: 18))
            if message.role != .user { Spacer(minLength: 40) }
        }
    }

    @ViewBuilder
    private var content: some View {
        if message.role == .error {
            Text(message.text).font(.callout).foregroundColor(.ccError)
        } else if message.text.isEmpty && streaming {
            HStack(spacing: 10) {
                ProgressView().tint(.ccTerracotta)
                Text("Thinking…").font(.callout).foregroundColor(.ccDim)
            }
        } else {
            Text(message.text)
                .foregroundColor(.ccBone)
                .textSelection(.enabled)
        }
    }

    private var bubbleColor: Color {
        message.role == .user ? .ccPanelHigh : .ccPanel
    }
}

private struct ToolChips: View {

    /// A tool and how many times it ran in this turn.
    private struct Use: Identifiable {
        let name: String
        var count: Int
        var id: String { name }
    }

    let tools: [String]

    var body: some View {
        // Collapse repeats ("Read ×3") so long tool runs stay short.
        let counted = tools.reduce(into: [Use]()) { acc, name in
            if let i = acc.firstIndex(where: { $0.name == name }) {
                acc[i].count += 1
            } else {
                acc.append(Use(name: name, count: 1))
            }
        }
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(counted) { tool in
                    Text(tool.count > 1 ? "⚙ \(tool.name) ×\(tool.count)" : "⚙ \(tool.name)")
                        .font(.caption)
                        .foregroundColor(.ccDim)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.ccToolChip, in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }
}

// MARK: - settings

/// Backend picker plus whatever that backend needs. Saved straight into
/// ClientConfig, so the Siri intent sees the same choice.
private struct SettingsSheet: View {

    enum Mode {
        /// First launch: nothing to go back to, so no Cancel.
        case setup
        case edit
    }

    let mode: Mode
    let onSave: () -> Void

    @State private var backend = ClientConfig.backend
    @State private var server = ClientConfig.server
    @State private var apiKey = ClientConfig.apiKey
    @State private var model = ClientConfig.model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Backend") {
                    Picker("Talk to", selection: $backend) {
                        ForEach(BackendKind.allCases) { kind in
                            Text(kind.label).tag(kind)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                switch backend {
                case .claudeCar:
                    Section {
                        TextField("host:port or full URL", text: $server)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    } header: {
                        Text("claude-car server")
                    } footer: {
                        Text("server.py on a host you run, reachable over the tailnet. It keeps the conversation and can use tools there.")
                    }
                case .anthropic:
                    Section {
                        SecureField("sk-ant-…", text: $apiKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("Model", text: $model)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: {
                        Text("Anthropic API")
                    } footer: {
                        Text("Your own key, kept in the keychain on this phone. The conversation is stored here too; there are no tools.")
                    }
                }
            }
            .navigationTitle(mode == .setup ? "Where is Claude?" : "Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if mode == .edit {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(mode == .setup ? "Start" : "Save") { save(); dismiss() }
                        .disabled(!complete)
                }
            }
        }
        .tint(.ccTerracotta)
    }

    /// The chosen backend's one required field is filled in.
    private var complete: Bool {
        switch backend {
        case .claudeCar: return !server.trimmingCharacters(in: .whitespaces).isEmpty
        case .anthropic: return !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func save() {
        ClientConfig.backend = backend
        ClientConfig.server = server.trimmingCharacters(in: .whitespacesAndNewlines)
        ClientConfig.apiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        ClientConfig.model = cleanModel.isEmpty ? AnthropicBackend.defaultModel : cleanModel
        onSave()
    }
}
