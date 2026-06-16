package com.contextsolutions.localagent.onboarding

import kotlinx.coroutines.flow.Flow

/**
 * Persistent state for the M6 Phase E first-run onboarding flow.
 *
 * Six independent gates:
 *
 *  - [languageDecided] (PR #97) — user has picked the app/response language on
 *    the very first onboarding screen (or kept the default). The language
 *    itself is persisted by `LanguagePreferences`; this flag only tracks "user
 *    was shown the picker", and it gates BEFORE every other step so the rest of
 *    onboarding renders in the chosen language.
 *  - [disclosureAcknowledged] — user has read and accepted the on-device
 *    privacy disclosure (PRD §6.1).
 *  - [braveKeyDecided] — user has either entered a Brave Search API key
 *    or explicitly chosen to skip ("Add later in Settings"). The
 *    presence-of-key check still drives the actual search-tool gating;
 *    this flag only tracks "user was shown the option".
 *  - [hfAuthTokenDecided] — user has either entered a HuggingFace API
 *    token (required to authenticate the gated Gemma 4 download for
 *    production builds) or explicitly chosen to skip. Same "shown the
 *    option" semantics as [braveKeyDecided] — the actual download still
 *    gates on token presence.
 *  - [locationDecided] (PR #23) — user has either captured a
 *    country/region/city for vertical search routing, or explicitly
 *    accepted the device-locale fallback. The location itself is
 *    persisted by `SearchPreferencesRepository`; this flag only tracks
 *    "user was shown the picker".
 *  - [telemetryDecided] — mirrors `TelemetryConsentManager.firstRunDecided`
 *    from Phase C. Kept here as a read-through cache so the host can
 *    compute "is onboarding complete?" from a single state object.
 *
 * Onboarding is complete when all six are true. The download screen +
 * "ready" screen are sequenced after onboarding by `MainScreen`.
 *
 * Implementation lives in `:shared/androidMain` backed by a plain
 * SharedPreferences file (non-secret booleans; same pattern as
 * MemoryPreferences + SharedPreferencesTelemetryConsentManager).
 */
interface OnboardingPreferences {

    fun languageDecided(): Boolean
    fun languageDecidedFlow(): Flow<Boolean>
    fun markLanguageDecided()

    fun disclosureAcknowledged(): Boolean
    fun disclosureAcknowledgedFlow(): Flow<Boolean>
    fun markDisclosureAcknowledged()

    fun braveKeyDecided(): Boolean
    fun braveKeyDecidedFlow(): Flow<Boolean>
    fun markBraveKeyDecided()

    fun hfAuthTokenDecided(): Boolean
    fun hfAuthTokenDecidedFlow(): Flow<Boolean>
    fun markHfAuthTokenDecided()

    fun locationDecided(): Boolean
    fun locationDecidedFlow(): Flow<Boolean>
    fun markLocationDecided()
}
