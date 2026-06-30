# Building & testing the app on iOS (iPhone)

How to build, run, and test the iOS app (PR #41) — written for a **physical
iPhone** (on-device LiteRT-LM needs real Metal hardware; see the Simulator note
below). The iOS app reuses the shared KMP core (`:shared`) and the shared
Compose-Multiplatform UI (`:ui`, exported as the `ComposeApp` framework); only a
thin SwiftUI shell + the LiteRT-LM Swift engine bridge live in `iosApp/`.

The Gradle project root is **`android-app/`** (run all `./gradlew` commands from
there). The Xcode project is **`android-app/iosApp/iosApp.xcodeproj`**.

## What works in this milestone

- On-device chat with **Gemma 4 E2B via the LiteRT-LM Swift API (Metal)**, streamed.
- Conversation history persisted (SQLDelight native driver).
- Settings, navigation, the shared UI.
- Optional remote chat (Settings → Remote LLM) over the Darwin HTTP engine.

Deferred to follow-ups (no-op stubs this milestone): voice (STT/TTS), image
input, web search, on-device memory/embeddings, relay/desktop-pairing, jobs.
The local SQLite DB is **unkeyed** on iOS (SQLCipher-on-iOS is deferred).

## Prerequisites

### 1. JDK 17 (Temurin)
Same as the desktop build — see [`MACOS_BUILD.md`](MACOS_BUILD.md). `export
JAVA_HOME="$(/usr/libexec/java_home -v 17)"`.

### 2. Xcode + an Apple Developer team
Xcode 15+ with the iOS SDK. A signing team (a **free personal team** is enough
for installing on your own device) configured in Xcode → the app target →
Signing & Capabilities.

### 3. GitHub Packages auth
The `:shared` build resolves the relay SDK during configuration, so the same
`gpr.user`/`gpr.key` (classic PAT, `repo` + `read:packages`) the desktop build
needs applies — see [`MACOS_BUILD.md`](MACOS_BUILD.md) §2.

### 4. The LiteRT-LM Swift package (one-time, in Xcode)
The on-device engine uses the official Swift package. In Xcode → **File → Add
Package Dependencies…** → `https://github.com/google-ai-edge/LiteRT-LM` → pin a
stable tag (**v0.13.1+**) → add the `LiteRTLM` library to the `iosApp` target.
(The Kotlin side never references it — it's consumed only by
`iosApp/iosApp/LiteRtBridge.swift`.)

## Build & run

1. Open `android-app/iosApp/iosApp.xcodeproj` in Xcode.
2. Select your team under Signing & Capabilities; set a unique bundle id.
3. Pick your connected iPhone (or an iOS Simulator — UI/remote only, see below).
4. Run (⌘R).

The Xcode build runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` (a Run
Script build phase) to build + embed the `ComposeApp.framework`, then compiles
the Swift shell against it.

**First launch** downloads the Gemma `.litertlm` (~2.58 GB) into the app's
Application Support dir with a progress screen; chat unlocks when the download
completes (verified by byte size; the file is excluded from iCloud backup).

CLI build of the framework (what CI gates on — no Xcode):
```bash
cd android-app
./gradlew :shared:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosSimulatorArm64
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
```
Full Simulator app build:
```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 15' build
```

## ⚠️ On-device LLM does NOT run on the iOS Simulator

LiteRT-LM inference works on a **physical iPhone** (and macOS) only — on the iOS
Simulator CPU inference crashes and Metal is unavailable
([google-ai-edge/LiteRT-LM#2504](https://github.com/google-ai-edge/LiteRT-LM/issues/2504)).
On the Simulator, use the UI + the **remote** chat path (Settings → Remote LLM →
point at a reachable Ollama / OpenAI-compatible server) to exercise chat. CI is
therefore build-only (compile + framework link).

## Test

```bash
cd android-app
# Shared-core iOS unit suite (Kotlin/Native):
./gradlew :shared:iosSimulatorArm64Test
```
CI runs the compile + framework-link gates on `macos-latest` via
[`.github/workflows/ios-test.yml`](../.github/workflows/ios-test.yml).

### Acceptance (physical iPhone)
1. App launches → onboarding → model-download gate fetches Gemma; chat unlocks.
2. Send a message → Gemma 4 E2B runs **on-device via Metal**, reply streams;
   the load banner shows GPU/CPU.
3. Relaunch → conversation history persists.
4. Cancel mid-generation stops promptly.
5. Settings opens; configuring a remote Ollama routes there instead of local.

## Architecture

| Concern | Lives in | Notes |
|---|---|---|
| Shared UI / navigation / chat / persistence | `:ui` commonMain (`ComposeApp` framework) | unchanged across platforms |
| On-device LLM | Swift `LiteRtBridge` → Kotlin `NativeLlmBridge` → `LiteRtIosInferenceEngine` | the `local` engine inside `RoutingInferenceEngine` |
| Networking | `IosHttpEngineFactory` (Ktor Darwin) | Ollama/search/link |
| Secrets | `IosSecureStorage` (Keychain) | Ollama/Brave keys |
| Database | `IosDatabaseFactory` (`NativeSqliteDriver`, unkeyed) | |
| DI | `iosModule` + `IosEntryPointKt.doInitKoin(bridge)` | mirrors `androidModule` |

## iOS gotchas

- **iOS toolchain artifacts are pinned** in `gradle/verification-metadata.xml`
  (Kotlin/Native prebuilt, Skiko iOS, Ktor-Darwin). A dependency bump that pulls
  a new iOS native fails verification and names it — regenerate per the recipe in
  `CLAUDE.md`. (Generate iOS entries on macOS: the natives are macOS-resolvable.)
- **App data / model** live in the app sandbox's Application Support dir; delete
  the app to force a clean first-run.
- **LiteRT-LM Swift symbol names** in `LiteRtBridge.swift` are a scaffold against
  the documented API — adjust to the package's current surface when wiring
  on-device (the bridge contract on the Kotlin side is stable).
