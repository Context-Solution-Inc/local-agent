import SwiftUI
import ComposeApp

// Local Agent iOS app entry point (PR #41).
//
// Wires the Swift on-device LLM bridge into the shared Koin graph, then hosts the
// shared Compose-Multiplatform UI. The Kotlin side (`:ui` ComposeApp framework)
// owns navigation, screens, chat, persistence; Swift owns only the LiteRT-LM
// engine (via LiteRtBridge) and the UIViewController host.
@main
struct iOSApp: App {
    init() {
        // Start Koin with the Swift LiteRT-LM bridge as the on-device engine.
        // `IosEntryPointKt` is generated from shared/.../ios entry point (Phase E).
        IosEntryPointKt.doInitKoin(bridge: LiteRtBridge())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
