package com.contextsolutions.localagent.i18n

/**
 * Maps an ISO country code (as carried by `locations.json` / `search_defaults.json`)
 * to its localizable [StringKeys] display-name key (PR #98). Only the four
 * countries the catalog ships (CA / US / GB / AU) are keyed; any other code
 * returns `null` so the caller falls back to the raw `locations.json` name —
 * never a bare key. Region and city names stay as data (proper nouns).
 *
 * Resolve through the active [Strings] at the callsite:
 *  - Compose:  `CountryDisplay.keyFor(code)?.let { tr(it) } ?: fallbackName`
 *  - agent:    `CountryDisplay.keyFor(code)?.let { strings.get(it) } ?: fallbackName`
 */
object CountryDisplay {
    fun keyFor(code: String): String? = when (code.trim().uppercase()) {
        "CA" -> StringKeys.DATA_COUNTRY_CA
        "US" -> StringKeys.DATA_COUNTRY_US
        "GB" -> StringKeys.DATA_COUNTRY_GB
        "AU" -> StringKeys.DATA_COUNTRY_AU
        else -> null
    }
}
