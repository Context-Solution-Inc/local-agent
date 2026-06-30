# iosApp — the iOS/iPhone app (PR #41)

The Xcode app shell that hosts the shared Compose-Multiplatform UI (`:ui`, the
`ComposeApp` framework) and bridges the on-device LiteRT-LM Swift engine into the
shared Koin graph. Full build/run/test guide: [`../../docs/IOS_BUILD.md`](../../docs/IOS_BUILD.md).

- `iosApp.xcodeproj` — the Xcode project (target `iosApp`, scheme `iosApp`). A Run
  Script build phase runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` to
  build + embed `ComposeApp.framework`.
- `iosApp/iOSApp.swift` — `@main` SwiftUI app; calls `IosEntryPointKt.doInitKoin(bridge:)`.
- `iosApp/ContentView.swift` — hosts `IosEntryPointKt.MainViewController()` (the shared UI).
- `iosApp/LiteRtBridge.swift` — implements the Kotlin `NativeLlmBridge` using the
  official LiteRT-LM Swift package (Metal). **Scaffold** against the documented API —
  finalize the symbol names on-device.
- `iosApp/Info.plist`, `Assets.xcassets/` — app metadata + launcher icon (`icon-1024.png`,
  opaque 1024×1024, universal). Regenerate via `desktopApp/icons/generate_icons.py`.

**One-time Xcode setup** (can't be done headlessly): add the LiteRT-LM Swift Package
(`https://github.com/google-ai-edge/LiteRT-LM`, tag v0.13.1+) to the `iosApp` target,
and select your signing team. On-device LLM needs a **physical iPhone** (not the
Simulator — LiteRT-LM #2504).
