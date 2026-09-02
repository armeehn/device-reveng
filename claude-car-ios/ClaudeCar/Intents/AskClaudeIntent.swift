import AppIntents
import Foundation

/// "Hey Siri, ask Claude …" — a whole turn without touching the phone.
///
/// The question is dictated by Siri, the answer is spoken back, and both land in
/// the same conversation the app shows, whichever backend is selected.
struct AskClaudeIntent: AppIntent {

    static var title: LocalizedStringResource = "Ask Claude"
    static var description = IntentDescription(
        "Ask Claude a question and hear the answer, without opening the app."
    )

    /// Stay in Siri: opening the app is the last thing you want while driving.
    static var openAppWhenRun = false

    /// The phone must be unlocked. A claude-car backend can read files on its
    /// host, and the Anthropic key bills an account — a locked phone is not a
    /// microphone into either.
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Question", requestValueDialog: "What do you want to ask?")
    var question: String

    static var parameterSummary: some ParameterSummary {
        Summary("Ask Claude \(\.$question)")
    }

    /// Spoken answers are listened to, not read: without this nudge Claude
    /// replies with headings and bullet lists that Siri reads out verbatim.
    private static let spokenStyle =
        "Answer out loud in two sentences or fewer. Plain prose, no lists, no code.\n\n"

    /// Siri's budget for an intent is short and undocumented; this stays well
    /// inside it and leaves room to speak the result.
    private static let budget: TimeInterval = 20

    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard ClientConfig.isConfigured else {
            return .result(dialog: "Claude isn't set up yet. Open the app and choose a backend.")
        }
        let backend = Backends.current()
        let history = await backend.conversation()
        let answer = await backend.answer(
            Self.spokenStyle + question,
            history: history,
            within: Self.budget
        )

        // A stateless backend only remembers what we write down.
        if answer.complete, !answer.text.isEmpty {
            await backend.persist(history + [
                Message(role: .user, text: question),
                Message(role: .assistant, text: answer.text),
            ])
        }

        return .result(dialog: IntentDialog(stringLiteral: spoken(answer)))
    }

    private func spoken(_ answer: SpokenAnswer) -> String {
        if answer.text.isEmpty, let failure = answer.failure {
            return "Claude couldn't answer. \(failure)"
        }
        if answer.text.isEmpty {
            return answer.complete
                ? "Claude didn't say anything."
                : "Claude is still working on it. The answer will be in the app."
        }
        return answer.complete
            ? answer.text
            : answer.text + " That's as far as it got. The rest is in the app."
    }
}

/// Registers the spoken phrases, so the intent works the moment the app is
/// installed — no trip through the Shortcuts app first. Every phrase must
/// contain the app name; INAlternativeAppNames in Info.plist adds "Claude Car"
/// as a synonym, since "Claude" alone is easy for Siri to mishear.
struct ClaudeCarShortcuts: AppShortcutsProvider {

    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AskClaudeIntent(),
            phrases: [
                "Ask \(.applicationName)",
                "Ask \(.applicationName) a question",
                "Talk to \(.applicationName)",
            ],
            shortTitle: "Ask Claude",
            systemImageName: "sparkles"
        )
    }
}
