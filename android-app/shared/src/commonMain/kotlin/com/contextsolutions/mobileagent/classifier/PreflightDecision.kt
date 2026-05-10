package com.contextsolutions.mobileagent.classifier

/**
 * Outcome of [PreflightRouter.route] for a single user turn. The agent
 * loop branches on the variant before invoking Gemma per PRD §3.2.1.
 */
sealed class PreflightDecision {

    /** The probability the classifier assigned to "search_required", if computed. */
    abstract val pSearchRequired: Float?

    /**
     * High-band hit (`p_search_required > highBand`) AND the query rewriter
     * produced a confident search query. The agent fires Brave Search with
     * [rewrittenQuery], injects the result as a synthetic tool message, and
     * passes `preflightNotice = true` to the prompt assembler so the
     * `[PRE-FLIGHT NOTICE BLOCK]` (SYSTEM_PROMPT.md §6) is emitted.
     */
    data class FireSearch(
        val originalQuery: String,
        val rewrittenQuery: String,
        override val pSearchRequired: Float,
    ) : PreflightDecision()

    /**
     * Low-band hit (`p_search_required < lowBand`). Per M4_PLAN.md §2 the
     * `web_search` tool stays registered — Gemma can still call it if its
     * own judgment differs — but pre-flight does NOT pre-execute a search.
     */
    data class SkipSearch(
        override val pSearchRequired: Float,
    ) : PreflightDecision()

    /**
     * Middle band, OR rewriter could not produce a confident query, OR
     * classifier is unavailable. Behavior matches the M2 path: standard
     * tool-calling, Gemma decides whether to search.
     */
    data class FallThrough(
        val reason: FallThroughReason,
        override val pSearchRequired: Float?,
    ) : PreflightDecision()

    /**
     * Search is disabled in settings (toggle off, or no Brave key). Pre-flight
     * never fires regardless of classifier output. The router short-circuits
     * before tokenizing to save the inference cost.
     */
    data object SearchDisabled : PreflightDecision() {
        override val pSearchRequired: Float? = null
    }
}

/** Reason a high-band candidate fell through instead of firing search. */
enum class FallThroughReason {
    /** `p_search_required` landed in `[lowBand, highBand]`. */
    MiddleBand,

    /**
     * High-band hit but the deterministic rewriter aborted (e.g., the query
     * contains a possessive reference like "my team" that requires memory
     * context unavailable until M5).
     */
    RewriterAbort,

    /**
     * Classifier failed to load or threw during inference. Engine returned
     * null. Logged once per app lifetime; subsequent calls take this path
     * silently.
     */
    ClassifierUnavailable,
}
