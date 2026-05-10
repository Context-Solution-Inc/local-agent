package com.contextsolutions.mobileagent.classifier

import com.contextsolutions.mobileagent.agent.TimeContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Deterministic-only query rewriter for the Phase C [PreflightRouter] —
 * resolves relative time expressions in user queries to concrete dates
 * using the same [TimeContext] the prompt assembler injects, and aborts
 * (returns `null`) on queries that need memory context to disambiguate.
 *
 * **PRD §3.2.1 v1 scope (M4_PLAN.md §2 ratified):**
 *
 *  - Date/time substitution from [TimeContext]: today, tonight, this morning,
 *    this afternoon, this evening, this week, this month, this year,
 *    yesterday, last night, last week, last month, last year.
 *  - Memory-reference abort: queries containing possessives ("my team",
 *    "my company", "where I live", …) return `null` so the router emits
 *    `FallThrough(RewriterAbort)`. M5 will replace this with retrieval-
 *    backed substitution.
 *  - Empty / one-token output → `null` (nothing left to search for).
 *
 * **Out of scope for v1:** Gemma fallback for complex rewrites (defeats the
 * round-trip-saving purpose), abbreviation expansion (search engines handle
 * NFL/S&P/etc. fine; revisit if telemetry shows under-performance).
 *
 * The rewriter is pure-Kotlin and lives in commonMain so iOS gets it for
 * free in Phase 2.
 */
class QueryRewriter(
    private val timeContextProvider: () -> TimeContext,
) {

    /**
     * Returns the rewritten query, or `null` to abort and FallThrough.
     */
    fun rewrite(originalQuery: String): String? {
        val trimmed = originalQuery.trim()
        if (trimmed.isEmpty()) return null
        if (containsMemoryReference(trimmed)) return null

        val context = timeContextProvider()
        val rewritten = applyDateTimeSubstitutions(trimmed, context)

        val collapsed = rewritten.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.split(' ').size < 2) return null

        return collapsed
    }

    // -- Memory-reference detection ----------------------------------------

    /**
     * Conservative match: any "my X" possessive aborts, plus a small set of
     * first-person spatial/temporal phrases. False positives just send a
     * query to standard Gemma tool-calling instead of pre-flight, which is
     * the documented fallback (PRD §3.2.1). False negatives — rewriting a
     * memory-dependent query into nonsense — are the worse failure mode.
     */
    private fun containsMemoryReference(query: String): Boolean {
        val lower = query.lowercase()
        return MEMORY_REFERENCE_REGEX.containsMatchIn(lower)
    }

    // -- Date/time substitution --------------------------------------------

    /**
     * Apply the rules in **most-specific-first** order so multi-word phrases
     * ("last night", "last week") are caught before their constituent
     * single words ("last" alone is never substituted).
     */
    private fun applyDateTimeSubstitutions(query: String, context: TimeContext): String {
        var result = query
        for (rule in dateTimeRules(context)) {
            result = rule.regex.replace(result, rule.replacement)
        }
        return result
    }

    private fun dateTimeRules(context: TimeContext): List<RewriteRule> {
        val today = LocalDate(context.now.year, context.now.monthNumber, context.now.dayOfMonth)
        val yesterday = today.minus(DatePeriod(days = 1))
        val lastWeekStart = today.minus(DatePeriod(days = 7))
        val lastMonthFirst = today.minus(DatePeriod(months = 1))

        // Order matters: longer phrases first.
        return listOf(
            RewriteRule(wordRegex("last night"), iso(yesterday) + " evening"),
            RewriteRule(wordRegex("yesterday evening"), iso(yesterday) + " evening"),
            RewriteRule(wordRegex("yesterday morning"), iso(yesterday) + " morning"),
            RewriteRule(wordRegex("yesterday afternoon"), iso(yesterday) + " afternoon"),
            RewriteRule(wordRegex("yesterday"), iso(yesterday)),
            RewriteRule(wordRegex("this morning"), iso(today) + " morning"),
            RewriteRule(wordRegex("this afternoon"), iso(today) + " afternoon"),
            RewriteRule(wordRegex("this evening"), iso(today) + " evening"),
            RewriteRule(wordRegex("tonight"), iso(today) + " evening"),
            RewriteRule(wordRegex("today"), iso(today)),
            RewriteRule(wordRegex("last week"), "week of " + iso(lastWeekStart)),
            RewriteRule(wordRegex("this week"), "week of " + iso(today)),
            RewriteRule(wordRegex("last month"), monthName(lastMonthFirst.monthNumber) + " " + lastMonthFirst.year),
            RewriteRule(wordRegex("this month"), monthName(today.monthNumber) + " " + today.year),
            RewriteRule(wordRegex("last year"), (today.year - 1).toString()),
            RewriteRule(wordRegex("this year"), today.year.toString()),
        )
    }

    private fun iso(date: LocalDate): String =
        "${date.year.toString().padStart(4, '0')}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"

    private fun monthName(n: Int): String = when (n) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> error("invalid month $n")
    }

    private data class RewriteRule(val regex: Regex, val replacement: String)

    private fun wordRegex(phrase: String): Regex =
        Regex("(?i)\\b" + Regex.escape(phrase) + "\\b")

    private companion object {
        // Matches first-person possessives + a small set of "where I"/"when I"
        // constructs that strongly suggest the query needs memory context to
        // disambiguate. Intentionally permissive — false positives just route
        // to standard Gemma tool-calling, which is the documented fallback.
        private val MEMORY_REFERENCE_REGEX = Regex(
            """\b(?:""" +
                "my\\s+\\w+|" +                 // my X (any noun)
                "where\\s+i\\s+(?:live|work|study)|" +
                "the\\s+place\\s+where\\s+i\\s+(?:live|work|study)|" +
                "when\\s+i\\s+(?:was|got|moved|started|joined)|" +
                "i\\s+(?:live|work|study)\\s+(?:in|at|for)" +
                """)\b"""
        )
    }
}
