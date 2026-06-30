package com.contextsolutions.localagent.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Tiny string→string store persisted as a single JSON object file (PR #41), the
 * iOS counterpart of desktop's [DesktopJsonStore] / Android's `SharedPreferences`.
 * The file lives under the iOS app's `NSDocumentDirectory` (one file per repo, as
 * on desktop). Same public API surface as [DesktopJsonStore]
 * (`getString`/`putString`/`remove`) so the shared preference repos compose it
 * identically.
 *
 * Robust to a missing/corrupt file (starts empty). Reads/writes serve from an
 * in-memory map; preference writes are infrequent (settings saves). Constructed
 * with the bare file name (e.g. `"ollama_prefs.json"`).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosJsonStore(fileName: String) {
    private val json = Json { isLenient = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val path: String = documentsPath(fileName)
    private val map: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> = try {
        val raw: String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        if (raw != null) json.decodeFromString(serializer, raw).toMutableMap() else mutableMapOf()
    } catch (_: Throwable) {
        mutableMapOf()
    }

    fun getString(key: String): String? = map[key]

    fun putString(key: String, value: String) {
        map[key] = value
        persist()
    }

    fun remove(key: String) {
        if (map.remove(key) != null) persist()
    }

    private fun persist() {
        val content = json.encodeToString(serializer, map)
        (content as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    private companion object {
        fun documentsPath(fileName: String): String {
            val base = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String ?: error("no Documents dir")
            return "$base/$fileName"
        }
    }
}
