import AVFoundation
import Combine
import Foundation
import Speech

/// The in-app mic: live transcription into the draft field. Tap to start, tap
/// again or go quiet to stop. On-device recognition when the phone has it, so
/// it works in a tunnel and nothing leaves the car.
@MainActor
final class SpeechInput: ObservableObject {

    @Published private(set) var listening = false
    @Published private(set) var transcript = ""
    @Published private(set) var denied = false

    /// A pause this long ends the utterance; long enough for a mid-sentence
    /// breath, short enough that the field isn't hanging open in traffic.
    private static let silenceTimeout: TimeInterval = 2

    private let recognizer = SFSpeechRecognizer()
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silence: Timer?

    func toggle() {
        if listening {
            stop()
        } else {
            Task { await start() }
        }
    }

    private func start() async {
        guard await authorized() else {
            denied = true
            return
        }
        guard let recognizer, recognizer.isAvailable else {
            denied = true
            return
        }

        transcript = ""
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        self.request = request

        do {
            // Duck the car's audio rather than silence it; music comes back on stop.
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            engine.prepare()
            try engine.start()
        } catch {
            teardown()
            return
        }

        listening = true
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.transcript = result.bestTranscription.formattedString
                    self.restartSilenceTimer()
                    if result.isFinal { self.stop() }
                }
                if error != nil { self.stop() }
            }
        }
    }

    func stop() {
        guard listening else { return }
        listening = false
        teardown()
    }

    private func teardown() {
        silence?.invalidate()
        silence = nil
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func restartSilenceTimer() {
        silence?.invalidate()
        silence = Timer.scheduledTimer(withTimeInterval: Self.silenceTimeout, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.stop() }
        }
    }

    /// Both permissions, asked in order. Either refusal is final for this run.
    private func authorized() async -> Bool {
        let speech = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0 == .authorized) }
        }
        guard speech else { return false }
        return await AVAudioApplication.requestRecordPermission()
    }
}
