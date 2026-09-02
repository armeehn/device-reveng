// Typecheck-only shim: the iOS SwiftUI modifiers the macOS SDK lacks, so the
// real UI files can be checked here unchanged. Never part of the app.
import SwiftUI
enum TextInputAutocapitalization { case never }
enum UIKeyboardType { case URL }
extension View {
    func textInputAutocapitalization(_ v: TextInputAutocapitalization) -> some View { self }
    func keyboardType(_ v: UIKeyboardType) -> some View { self }
    func fullScreenCover<C: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> C) -> some View { self }
}
enum NavigationBarItem { enum TitleDisplayMode { case inline } }
extension View {
    func navigationBarTitleDisplayMode(_ m: NavigationBarItem.TitleDisplayMode) -> some View { self }
}
import AVFoundation
// AVAudioSession is iOS-only; the shape SpeechInput uses.
final class AVAudioSession {
    enum Category { case record }
    enum Mode { case measurement }
    struct CategoryOptions: OptionSet { let rawValue: Int; static let duckOthers = CategoryOptions(rawValue: 1) }
    struct SetActiveOptions: OptionSet { let rawValue: Int; static let notifyOthersOnDeactivation = SetActiveOptions(rawValue: 1) }
    static func sharedInstance() -> AVAudioSession { AVAudioSession() }
    func setCategory(_ c: Category, mode: Mode, options: CategoryOptions) throws {}
    func setActive(_ on: Bool, options: SetActiveOptions) throws {}
}
