package com.contextsolutions.localagent.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the bundled Spanish pack (PR #98). The English floor is verified by
 * [I18nTest]; this validates the *translated* `strings_es.json` shipped in
 * `shared/src/desktopMain/resources/i18n/` (byte-identical to the Android
 * assets copy):
 *
 *  - it parses, and every key it carries is a real [StringKeys] key (catches
 *    typos in the ~530-key file — a typo'd key would silently never apply); and
 *  - every value's placeholder set matches the English floor's, so a dropped or
 *    duplicated `%1$s`/`%1$d`/`%%` can't slip through and break formatting.
 *
 * It does NOT require 100% coverage (missing keys legitimately fall back to
 * English), so adding a new English key never breaks this test before it's
 * translated.
 */
class SpanishPackTest {

    private val placeholder = Regex("""%\d+\$[sd]|%%""")
    private fun placeholders(s: String): List<String> = placeholder.findAll(s).map { it.value }.sorted().toList()

    private fun loadEsPack(): StringPack {
        val text = javaClass.classLoader.getResourceAsStream("i18n/strings_es.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        assertTrue(text != null, "i18n/strings_es.json must be on the desktop resources classpath")
        return StringPack.parse(text, "es")
    }

    @Test
    fun every_es_key_is_a_known_StringKey() {
        val es = loadEsPack()
        val known = StringKeys.ALL.toSet()
        val unknown = es.keys - known
        assertTrue(unknown.isEmpty(), "strings_es.json has unknown/typo'd keys: $unknown")
    }

    @Test
    fun es_placeholders_match_the_english_floor() {
        val es = loadEsPack()
        val en = EnglishStrings.pack
        es.keys.forEach { key ->
            // Simple
            es.simple(key)?.let { esVal ->
                en.simple(key)?.let { enVal ->
                    assertEquals(placeholders(enVal), placeholders(esVal), "placeholder mismatch for simple key '$key'")
                }
            }
            // Plural — compare per CLDR form that exists in both
            es.pluralForms(key)?.let { esForms ->
                en.pluralForms(key)?.let { enForms ->
                    val enPh = enForms["other"]?.let { placeholders(it) }.orEmpty()
                    esForms.forEach { (form, esVal) ->
                        assertEquals(enPh, placeholders(esVal), "placeholder mismatch for plural key '$key' form '$form'")
                    }
                }
            }
            // Listed — same length
            es.list(key)?.let { esList ->
                en.list(key)?.let { enList ->
                    assertEquals(enList.size, esList.size, "list length mismatch for key '$key'")
                }
            }
        }
    }
}
