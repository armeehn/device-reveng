import SwiftUI

@main
struct ClaudeCarApp: App {

    @StateObject private var model = ChatModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ChatView(model: model)
                // Warm dark palette throughout: this is read in a car, at night.
                .preferredColorScheme(.dark)
                .task { model.start() }
                // Coming back to the app picks up whatever was asked through
                // Siri while it was in the background.
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.refresh() }
                }
        }
    }
}
