import Foundation
import ComposeApp
// The official LiteRT-LM Swift package (added via Xcode → Add Package Dependencies,
// https://github.com/google-ai-edge/LiteRT-LM). Pin a stable tag (v0.13.1+).
import LiteRTLM

// Swift implementation of the Kotlin `NativeLlmBridge` (PR #41) using the official
// LiteRT-LM Swift API (Metal GPU + CPU fallback, Gemma 4 E2B, streaming). Registered
// into the Koin graph by `iOSApp.init` so `RoutingInferenceEngine`'s local engine on
// iOS is `LiteRtIosInferenceEngine(this)`.
//
// IMPORTANT (on-device only): LiteRT-LM inference runs on a PHYSICAL iPhone, not the
// iOS Simulator (Simulator CPU inference crashes, Metal is unavailable — see
// google-ai-edge/LiteRT-LM#2504). The Simulator exercises only the UI + the remote
// Ollama path.
//
// NOTE: the exact LiteRT-LM Swift symbol names (Engine/Conversation initializers,
// the streaming API) may differ from this scaffold — adjust to the package's current
// surface when wiring on-device. The structure (load → per-turn Conversation → stream
// tokens → done/cancel) matches the documented API.
final class LiteRtBridge: NativeLlmBridge {

    private var engine: Engine?

    func load(
        modelPath: String,
        useGpu: Bool,
        enableVision: Bool,
        onLoaded: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        Task.detached {
            do {
                // Metal first; the package falls back to CPU internally when Metal
                // init fails. `EngineConfig` exposes backend + vision + cacheDir.
                let config = EngineConfig(
                    modelPath: modelPath,
                    backend: useGpu ? .gpu : .cpu,
                    maxNumImages: enableVision ? 1 : 0
                )
                self.engine = try Engine(config: config)
                onLoaded(useGpu ? "gpu" : "cpu")
            } catch {
                onError("LiteRT-LM load failed: \(error.localizedDescription)")
            }
        }
    }

    func generate(
        systemInstruction: String?,
        turns: [NativeChatTurn],
        imageBytes: KotlinByteArray?,
        onToken: @escaping (String) -> Void,
        onDone: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) -> NativeGenHandle {
        let task = Task.detached {
            do {
                guard let engine = self.engine else {
                    onError("model not loaded")
                    return
                }
                let conversation = try engine.createConversation(
                    systemInstruction: systemInstruction
                )
                // Replay prior turns so Gemma's chat template carries history; the
                // last turn is the current user message we stream a reply to.
                for turn in turns.dropLast() {
                    conversation.addMessage(role: turn.role, text: turn.text)
                }
                let last = turns.last
                let prompt = last?.text ?? ""

                for try await chunk in conversation.sendMessageStream(prompt) {
                    if Task.isCancelled { onDone("cancelled"); return }
                    onToken(chunk)
                }
                onDone("stop")
            } catch {
                if Task.isCancelled {
                    onDone("cancelled")
                } else {
                    onError("LiteRT-LM generate failed: \(error.localizedDescription)")
                }
            }
        }
        return TaskGenHandle(task: task)
    }

    func unload() {
        engine = nil
    }
}

/// Bridges Kotlin `NativeGenHandle.cancel()` to a Swift `Task` cancellation.
private final class TaskGenHandle: NativeGenHandle {
    private let task: Task<Void, Never>
    init(task: Task<Void, Never>) { self.task = task }
    func cancel() { task.cancel() }
}
