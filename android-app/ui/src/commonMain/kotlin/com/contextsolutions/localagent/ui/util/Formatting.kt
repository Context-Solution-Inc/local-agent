package com.contextsolutions.localagent.ui.util

import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

/**
 * Cross-platform UI formatting helpers (docs/DESKTOP_PORT_PLAN.md Phase 9).
 *
 * Replace the Android-only `android.text.format.DateUtils` /
 * `android.text.format.Formatter` the migrated screens used, so the screens
 * compile in shared `:ui` commonMain. Pure Kotlin (kotlinx-datetime) — no
 * platform dependency, identical output on Android and desktop.
 *
 * The relative-time words + month abbreviations route through the i18n catalog
 * ([Strings], PR #97); callers pass `LocalStrings.current`. The default
 * `Strings.ENGLISH` keeps English output byte-identical for any caller without
 * a catalog (tests, previews).
 */

/**
 * Abbreviated relative time, mirroring `DateUtils.getRelativeTimeSpanString`
 * with `FORMAT_ABBREV_RELATIVE` + a 1-minute minimum resolution: "now",
 * "5m ago" / "in 5m", "3h ago", "2d ago", and an absolute "MMM d" / "MMM d,
 * yyyy" date once the gap exceeds a week. Handles past and future.
 */
fun formatRelativeTime(epochMs: Long, nowMs: Long, strings: Strings = Strings.ENGLISH): String {
    val deltaMs = nowMs - epochMs
    val past = deltaMs >= 0
    val mins = abs(deltaMs) / 60_000
    return when {
        mins < 1 -> strings.get(StringKeys.FMT_NOW)
        mins < 60 -> relative(mins, "m", past, strings)
        mins < 60 * 24 -> relative(mins / 60, "h", past, strings)
        mins < 60 * 24 * 7 -> relative(mins / (60 * 24), "d", past, strings)
        else -> absoluteDate(epochMs, nowMs, strings)
    }
}

private fun relative(value: Long, unit: String, past: Boolean, strings: Strings): String =
    if (past) strings.get(StringKeys.FMT_AGO, "$value$unit") else strings.get(StringKeys.FMT_IN, "$value$unit")

private fun absoluteDate(epochMs: Long, nowMs: Long, strings: Strings): String {
    val tz = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date
    val nowYear = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date.year
    val month = strings.list(StringKeys.FMT_MONTHS)[date.monthNumber - 1]
    return if (date.year == nowYear) strings.get(StringKeys.FMT_DATE_SHORT, month, date.dayOfMonth)
    else strings.get(StringKeys.FMT_DATE_YEAR, month, date.dayOfMonth, date.year)
}

/**
 * Extract the host of a URL for the citation chip label (replaces Android's
 * `Uri.parse(url).host`). Strips scheme + userinfo + port + path; returns null
 * if there's nothing host-like, so the caller falls back to the full URL.
 */
fun urlHost(url: String): String? {
    val afterScheme = url.substringAfter("://", url)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfter('@')
    val host = hostPort.substringBefore(':').trim()
    return host.ifBlank { null }
}
