package com.contextsolutions.localagent.inference

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.writeData

/**
 * The on-device model spec for iOS (PR #41). iOS consumes the SAME `.litertlm`
 * artifact as Android (LiteRT-LM Swift loads it), from the public models CDN
 * (invariant #61). Pinned size matches Android's `ModelInventory.SPEC`.
 */
object IosModelSpec {
    const val FILENAME = "gemma-4-E2B-it.litertlm"
    const val URL = "https://downloads.contextsolutions.com/models/gemma-4-E2B-it.litertlm"
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val SIZE_BYTES = 2_588_147_712L
}

/**
 * iOS model inventory + resumable downloader (PR #41). The Gemma `.litertlm` is
 * 2.58 GB so it is never bundled — it downloads on first run into the app's
 * Application Support dir (excluded from iCloud backup) and is verified by byte
 * size. (SHA-256 verification via CommonCrypto is a follow-up; the resumable size
 * check + the LiteRT-LM load failing loudly on a corrupt file are the backstop.)
 *
 * Nothing here is shared with the Android/desktop downloaders (both are
 * platform-coupled and fully duplicated, per the as-built design).
 */
@OptIn(ExperimentalForeignApi::class)
class IosModelStore {

    private val fileManager = NSFileManager.defaultManager

    /** `<AppSupport>/models` (created on demand). */
    private fun modelsDir(): String {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("no Application Support dir")
        val dir = "$base/models"
        if (!fileManager.fileExistsAtPath(dir)) {
            fileManager.createDirectoryAtPath(dir, true, null, null)
        }
        return dir
    }

    fun modelPath(): String = "${modelsDir()}/${IosModelSpec.FILENAME}"

    /** True when the model is present at its full expected size. */
    fun isPresent(): Boolean = fileSize(modelPath()) == IosModelSpec.SIZE_BYTES

    /** Bytes already on disk (0 if absent) — the resume offset. */
    fun downloadedBytes(): Long = fileSize(modelPath()).coerceAtLeast(0L)

    /**
     * Ensure the model is present, resuming a partial download. [onProgress] gets
     * 0..1. Returns the local path on success; throws on a network/IO failure (the
     * caller surfaces it on the download screen and offers retry).
     */
    suspend fun ensure(client: HttpClient, onProgress: (Float) -> Unit): String {
        val path = modelPath()
        if (isPresent()) {
            onProgress(1f)
            return path
        }
        var have = downloadedBytes()
        if (have > IosModelSpec.SIZE_BYTES) {
            // Corrupt/oversized partial — start over.
            fileManager.removeItemAtPath(path, null)
            have = 0L
        }
        if (have == 0L) fileManager.createFileAtPath(path, null, null)

        val handle = NSFileHandle.fileHandleForWritingAtPath(path)
            ?: error("cannot open $path for writing")
        handle.seekToEndOfFile()

        client.prepareGet(IosModelSpec.URL) {
            if (have > 0L) headers { append(HttpHeaders.Range, "bytes=$have-") }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(CHUNK)
            var written = have
            while (true) {
                val n = channel.readAvailable(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) {
                    handle.writeData(buffer.copyOf(n).toNSData())
                    written += n
                    onProgress((written.toFloat() / IosModelSpec.SIZE_BYTES).coerceIn(0f, 1f))
                }
            }
        }
        handle.closeAndReturnError(null)
        markExcludedFromBackup(path)
        if (!isPresent()) error("download incomplete (${downloadedBytes()}/${IosModelSpec.SIZE_BYTES})")
        return path
    }

    private fun fileSize(path: String): Long {
        val attrs = fileManager.attributesOfItemAtPath(path, null) ?: return -1L
        val size = attrs["NSFileSize"] as? platform.Foundation.NSNumber ?: return -1L
        return size.longLongValue
    }

    /** Keep the multi-GB model out of iCloud backups. */
    private fun markExcludedFromBackup(path: String) {
        val url = NSURL.fileURLWithPath(path)
        url.setResourceValue(true, forKey = platform.Foundation.NSURLIsExcludedFromBackupKey, error = null)
    }

    private companion object {
        const val CHUNK = 1 shl 16 // 64 KiB
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }
