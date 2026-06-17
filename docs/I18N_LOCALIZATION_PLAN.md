# Multi-language support via a runtime string catalog

> **Status (as-built across PR #96 → #98):**
> - **PR #96 (merged)** — foundation + `:shared` consumers: the runtime `StringCatalog`,
>   both-platform pack loaders, Koin wiring, the in-code English floor, the Compose seam, and
>   the deterministic formatters + `AgentLoop` replies resolving through the catalog.
> - **PR #97 (merged)** — the Compose UI migration: ~110 keys across every screen (`tr()`/
>   `trPlural()`/`trList()`), the Settings **Language picker** re-added, a **language-first
>   onboarding step**, and a partial `strings_es.json`.
> - **PR #98 (this branch)** — notification titles localized via `StringCatalog`
>   (`JobCompletionNotifier`/`TaskQueue`/desktop model-download), the 4 country display names
>   keyed (`CountryDisplay`), and the **full Spanish pack** (~529 keys) + a `SpanishPackTest`
>   guardrail.
>
> The catalog is now end-to-end for Spanish. Remaining gaps are in **Deferred** below.
> Each PR is gated on a Pixel 7 + desktop smoke before merge — see Verification.

## Context

Every user-visible string used to be a hardcoded literal. This effort makes the *displayed and
spoken* strings configurable by locale so a new language is **a pure data file with no
recompile** (bundled JSON now, optionally remote-downloaded later). One setting — the existing
`PreferredLanguage` (`language/PreferredLanguage.kt`, 10 languages, ISO codes,
`LanguagePreferences.preferredLanguageFlow()`, default EN) — drives UI chrome, the deterministic
chat cards, and the agent's deterministic replies.

The original plan was written in the **Hilt + Android-only + `:androidApp` screens** era. The
codebase has since moved to **Koin** DI, a Compose-Multiplatform **`:ui`** module that holds
every screen (shared by Android + desktop), and a **desktop** port (`:desktopApp`). This doc is
the as-built record for that reality.

### Decisions locked for PR #96

- **Scope:** the catalog foundation + the `:shared` consumers. The ~525 Compose UI literals are
  deferred to a follow-up — the seam is in place so that PR is a near-mechanical `tr(...)` swap.
- **System prompt stays English.** The `PromptAssembler` blocks (BASE_TEMPLATE, guidelines,
  search/memory notices) and model-facing payloads (tool results, the `[SEARCH CONTEXT]`
  truncation marker, the tool-limit message) are **not** localized — a 2B model follows English
  instructions more reliably and the blocks embed literal tokens (`[SEARCH CONTEXT]`,
  `<|tool_call>`). Only the existing `languageDirective` ("Respond in X") carries language.
- **English-only this PR.** No `strings_es.json` ships; overlay/fallback/plurals are proven by
  unit tests (and an in-test synthetic `es`/`ru` pack), not a bundled translation.

## Architecture

A unified runtime **`StringCatalog`** in `:shared/commonMain` (`i18n/`), keyed by stable string
IDs, **not** Android `res/values-xx/strings.xml`. Two deviations from the original sketch, both
deliberate:

1. **English is an in-code floor, not a bundled `strings_en.json`.** `EnglishStrings.pack` holds
   the English values in Kotlin. This guarantees English always resolves (a broken EN pack is
   impossible), keeps tests Android-/asset-free, and is *complete by construction* (a guardrail
   test asserts its keys equal `StringKeys.ALL`). Non-English packs are pure JSON parsed by
   `StringPack.parse` and **overlaid** on the floor — fully satisfying "new language = data
   file"; only the floor is in code.
2. **Deterministic formatters stay `object`s with a per-turn `strings` parameter** (rather than
   `object → class`). They were already DI-bound as objects (`single { WeatherResponseFormatter }`),
   so this is **zero binding changes** on either platform; the per-turn `Strings` flows in as a
   `format(..., strings)` argument instead of being held as instance state.

### Core types — `shared/src/commonMain/.../i18n/`

- **`StringValue`** — `Simple` / `Plural(forms)` / `Listed(items)`; the JSON value shape
  (string / object / array) selects the variant.
- **`StringPack`** — immutable parsed strings for one language + its resolved `PluralRule`.
  `StringPack.parse(json, code)` reuses the lenient `Json { ignoreUnknownKeys = true }`; reads
  `_meta.code` / `_meta.plurals`.
- **`EnglishStrings`** — the in-code English `StringPack` floor.
- **`Strings`** — the accessor consumers call: `get(key, vararg)`, `plural(key, count, vararg)`,
  `list(key)`. Wraps an active pack over the English fallback; missing keys resolve against the
  floor; a genuinely-unknown key returns the key itself (never crashes). `Strings.ENGLISH` is the
  default for tests/callers without a catalog.
- **`PluralRules`** — hand-written `when` over code; 10 languages collapse to 4 rules
  (`OTHER_ONLY` zh/ja/ko, `ENGLISH` en/de/es/it/pt, `FRENCH` 0..1→one, `RUSSIAN`
  one/few/many). No ICU; `other` is mandatory.
- **`formatPositional`** (internal) — hand-rolled `%<n>$[sd]` substitutor + `%%` → `%` (no
  `String.format` positional specifiers — JVM-only; `:shared` has an `iosMain` set).
- **`StringKeys`** — central `object` of `const val` IDs + `ALL` (the guardrail set). Consumers
  use `StringKeys.X`, never raw strings.
- **`StringPackLoader`** — `fun interface { suspend fun load(code): String? }`, **injected**
  (Koin), not expect/actual.
- **`StringCatalog` / `DefaultStringCatalog`** — `active: StateFlow<Strings>` (drives Compose,
  built from `preferredLanguageFlow`) + `stringsFor(language): Strings` (synchronous, per-turn
  agent use; reads `active.value` so no concurrency primitive is needed — `synchronized` /
  `@Volatile` aren't available in commonMain with `iosMain`).

### Koin wiring

- `agentCoreModule` (commonMain): `single<StringCatalog> { DefaultStringCatalog(loader = get(),
  languagePreferences = get()) }`. The `AgentLoopFactory` binding resolves
  `catalog.stringsFor(responseLanguage)` once per `create(...)` and passes `strings` into
  `AgentLoop`.
- `AndroidKoinModule`: `single<StringPackLoader> { ... }` reading
  `assets/i18n/strings_<code>.json` on `Dispatchers.IO` (null on `FileNotFoundException`).
- `DesktopModule`: `single<StringPackLoader> { ... }` reading the same path via
  `DesktopResources.readTextOrNull` (classpath). Future remote packs check app-data first — a
  one-line change, the seam already supports it.

No pack ships today, so both loaders return null and the catalog stays English.

### `:shared` consumers (migrated)

- `WeatherResponseFormatter`, `StockResponseFormatter`, `ClockResponseFormatter` (objects) +
  `TodoResponseFormatter` (class) — each `format(...)` gained a `strings: Strings =
  Strings.ENGLISH` param; user-visible words/sentences/labels + plural count-nouns route through
  `StringKeys`. Symbols/units (`°C`, `▲`, `·`, `$`, `•`), tool-supplied data, and digit
  formatting stay structural.
- `AgentLoop` — engine error, weather location prompt, weather disambiguation, clock/todo
  guidance, run-job messages (`run job …` prompt, not-found, no-output, failure), and the
  remember/forget acks resolve via the per-turn `strings`. The duplicated companion constants
  (`FRIENDLY_ENGINE_ERROR`, `WEATHER_LOCATION_PROMPT_TEXT`, `CLOCK_GUIDANCE_TEXT`,
  `TODO_GUIDANCE_TEXT`) were removed. `PromptAssembler` is unchanged (system prompt stays
  English).

### Compose seam — `ui/src/commonMain/.../i18n/LocalStrings.kt`

`val LocalStrings = staticCompositionLocalOf { Strings.ENGLISH }` + `@Composable
@ReadOnlyComposable` `tr` / `trPlural` / `trList`. Seeded at both composition roots from
`StringCatalog.active`: Android `MainActivity` (`koinInject<StringCatalog>().active`) and desktop
`Main.kt` (`koin.get<StringCatalog>().active`), each wrapping the theme in
`CompositionLocalProvider(LocalStrings provides strings)`. Every screen now reads `tr(...)`
(migrated in PR #97).

### Follow-up consumers (PR #97 — UI; PR #98 — notifications + data names)

- **Compose UI (PR #97):** every screen's literals route through `tr`/`trPlural`/`trList`; the
  Settings **Language picker** (`SettingsScreen`) and a **language-first onboarding step**
  (`LanguagePickerScreen`, gated by a new `OnboardingPreferences.languageDecided`) write
  `LanguagePreferences`, which the reactive `StringCatalog.active` turns into a live re-provide —
  so flipping the language switches the whole tree (and previews onboarding) immediately.
- **Notifications (PR #98):** off the Compose tree, consumers resolve `stringCatalog.active.value`
  (the same instance the picker drives) and key the **titles** —
  `JobCompletionNotifier`/`TaskQueue` (`:shared`) + `DesktopModelDownloadController` (`:desktopApp`).
  Bodies stay as data. Injected via Koin (`get()`); tests use an english-pinned `StringCatalog`
  double. Android framework-instantiated notifications are out of scope (see Deferred).
- **Country names (PR #98):** `CountryDisplay.keyFor(code)` maps the 4 ISO codes (CA/US/GB/AU) to
  `data.country.*`; the 3 callsites (`LocationPickerScreen`, `AgentLoop` weather disambiguation,
  `SearchSourcesScreen`) resolve through the active `Strings` with the `locations.json` name as
  fallback for any other code.
- **Spanish pack (PR #98):** `strings_es.json` reaches **full coverage** (~529 keys) in both
  byte-identical copies; `SpanishPackTest` (desktopTest) asserts no typo'd keys + placeholder
  parity vs the English floor (it does *not* require full coverage, so new English keys don't
  break it before translation).

## Files (as-built)

**New (`shared/src/commonMain/.../i18n/`):** `StringPack.kt`, `Strings.kt`, `StringCatalog.kt`,
`PluralRules.kt`, `PositionalFormatter.kt`, `StringPackLoader.kt`, `StringKeys.kt`,
`EnglishStrings.kt`. **New (`:ui`):** `ui/.../i18n/LocalStrings.kt`. **New (tests):**
`shared/src/commonTest/.../i18n/I18nTest.kt`, `shared/src/commonTest/.../agent/FormatterI18nParityTest.kt`.

**Modified:** the four formatters + `AgentLoop` + `di/AgentCoreModule` (`:shared/commonMain`);
`DesktopModule` (`:shared/desktopMain`); `AndroidKoinModule` + `MainActivity` (`:androidApp`);
`Main.kt` (`:desktopApp`).

**Added in PR #97:** `ui/.../onboarding/LanguagePickerScreen.kt`; the `onboarding.language*`
gate on `OnboardingPreferences` (+ Android/desktop impls) + `OnboardingViewModel`/`OnboardingHost`;
the Settings Language picker in `SettingsScreen.kt`; `strings_es.json` (partial) in both asset
roots. Plus the `tr(...)` migration across every `:ui` screen.

**Added in PR #98:** `shared/.../i18n/CountryDisplay.kt`; `shared/src/commonTest/.../i18n/TestStringCatalog.kt`
+ `shared/src/desktopTest/.../i18n/SpanishPackTest.kt`. **Modified:** `StringKeys`/`EnglishStrings`
(notif + country keys); `JobCompletionNotifier`, `TaskQueue`, `AgentLoop` (`:shared`);
`DesktopModelDownloadController` + `Main.kt` (`:desktopApp`); `AndroidKoinModule` + `DesktopModule`
(notifier bindings); `LocationPickerScreen` + `SearchSourcesScreen` (`:ui`); the now-**full**
`strings_es.json` (both copies).

## Verification

- **Unit tests (run on the desktop JVM, `:shared:desktopTest` — Android-free):**
  - `I18nTest` — guardrail (floor keys == `StringKeys.ALL`, no orphans, every plural has
    `other`), positional formatter (`%%`, reorder, out-of-range), `PluralRules` vs CLDR samples
    (Russian 1/2/5/21/11, French 0/1, CJK), and overlay/fallback/plurals via a synthetic
    `es`/`ru` pack (proves "new language = data file" without bundling one).
  - `FormatterI18nParityTest` — each formatter, with default `Strings.ENGLISH`, reproduces its
    pre-migration output **byte-for-byte** (golden stock bubble, clock plurals/durations, todo).
  - `SpanishPackTest` (PR #98, desktopTest) — loads the bundled `strings_es.json` from the
    classpath and asserts every key is a known `StringKey` (no typos) + placeholder parity vs the
    English floor.
  - All pass; the full `:shared:desktopTest` suite is green.
- **Compiles:** `:shared:compileKotlinDesktop`, `:ui:compileKotlinDesktop`,
  `:desktopApp:compileKotlin` all build clean.
- **Not run in this environment (no Android SDK):** `:androidApp:assembleDebug` and the
  `:androidApp` formatter/AgentLoop tests. The migrated logic lives in `:shared` and is covered
  by the commonTest parity suite, but the Android build + the on-device smoke below must be run
  before merge.
- **On-device smoke (Pixel 7) — the merge gate:** `./gradlew :androidApp:installDebug`; confirm
  weather/clock/todo/finance cards, the run-job output, and the remember/forget acks read
  **identically** to before (English unchanged). Repeat on the desktop app. Then, to prove the
  mechanism live, drop a partial `assets/i18n/strings_es.json` (+ the desktop
  `resources/i18n/strings_es.json`), flip Settings → language to Spanish, and confirm keyed
  strings switch while untranslated keys fall back to English.

## Done in follow-ups (was deferred)

- ✅ **The Compose UI literals** (Chat, Settings, the onboarding screens, memory, history,
  clock, todo, download, search sources, Jobs, + the desktop GPU/Voice/Link-pairing sections)
  — migrated to `tr(...)` in **PR #97**, plus the Settings Language picker and a language-first
  onboarding step.
- ✅ **A real bundled `strings_es.json`** — partial in #97, **completed to full coverage in
  PR #98** (~529 keys, both byte-identical copies), guarded by `SpanishPackTest`.
- ✅ **Notification strings** — job/task/model-download titles localized via `StringCatalog`
  in **PR #98** (`JobCompletionNotifier`, `TaskQueue`, `DesktopModelDownloadController`).
- ✅ **`locations.json` country display names** — the 4 country names keyed via `CountryDisplay`
  in **PR #98** (onboarding dropdown, weather disambiguation, Settings location line).

## Still deferred (tracked, not yet done)

- **Android framework-instantiated notifications** — alarm/timer-fired (`ClockNotifications`),
  the WorkManager `ModelDownloadWorker`, and the inference foreground service still read
  `res/values` strings.xml, which follows **device locale**, not the in-app `PreferredLanguage`.
  Routing them through the catalog needs a global/lazy `StringCatalog` resolver (they're
  framework-instantiated, not DI-constructed). PR #98 deliberately scoped to the catalog-reachable
  notifiers only.
- **Voice-command recognition phrases** (`VoiceCommand` match lists) — a *recognition* surface
  that must be authored against what each STT engine emits (QA-heavy); plus driving the TTS voice
  from `PreferredLanguage` rather than `Locale.getDefault()`.
- **Other-language packs** — only `strings_es.json` ships. The 8 other non-English
  `PreferredLanguage` entries (fr/de/it/pt/zh/ja/ko/ru) have no pack and fall back fully to
  English; each is a pure JSON data file (the `SpanishPackTest` pattern generalizes).
- **System-prompt block localization** (intentionally English now).
- **`JobExecutor` "(no output)"** (desktopMain subprocess placeholder) — left English; it has no
  `Strings` in scope and is a low-value edge.
- **`search_defaults.json` source display names + region/city names** — left as data (proper
  nouns / brands that shouldn't translate); only country names were keyed.
- **`search_cache` locale-keying bug** — cached results aren't keyed by language (needs a `.sqm`
  migration, invariant #20).
- Android `app_name` + notification **channel** names stay in `res/values/strings.xml` (the
  framework reads them outside Compose). Coverage is intentionally not 100%.

## Pitfalls (carried into the follow-up)

1. Plural-rule correctness is the highest bug density — Russian few/many, French 0, CJK
   other-only. Unit-tested in `I18nTest`; keep extending when a language is added.
2. No `String.format` positional specifiers from commonMain — use `formatPositional` / the
   `tr` accessors.
3. Keep `:shared` Android-free: the loader impls live in `AndroidKoinModule` / `DesktopModule`,
   never in `:shared/commonMain`.
4. Resolve `Strings` once per turn (agent) / per language flip (Compose) — never per token.
5. Plural forms whose `one`/`other` take *different* placeholders (e.g. a row vs a count) are
   modeled as **separate simple keys** with a code-level size branch, not one plural key — see
   `clock.alarms.one` / `clock.alarms.header`.
