package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Node runtime a job uses: a private install dir, or the system one (binDir == null). */
data class NodeRuntime(val binDir: File?)

/** Outcome of [DesktopNodeProvisioner.ensure]. */
sealed interface NodeResult {
    data class Available(val runtime: NodeRuntime) : NodeResult
    data class Failed(val reason: String) : NodeResult
}

/**
 * Ensures Node.js + npm are available for a job (PR #100). A job manifest opts in via
 * `init.requires: ["node"]`.
 *
 * Resolution order:
 *  1. A previously provisioned private Node under `<app-data>/runtimes/node` → use it.
 *  2. The system `node` + `npm` (both on `PATH`) → use them ([NodeRuntime.binDir] == null).
 *  3. Otherwise download the pinned Node LTS for this OS/arch and extract it to
 *     `<app-data>/runtimes/node` (a stable dir, so [DesktopJobRuntimeEnv] can add it to the
 *     job subprocess `PATH` both during init and at run time).
 *
 * The download is unverified-by-checksum for now (we validate by running `node --version`);
 * sha-pinning per platform is a follow-up. Override the dist mirror with
 * `LOCALAGENT_NODE_DIST_BASE` (e.g. for an offline test).
 */
class DesktopNodeProvisioner(
    private val baseDir: File = DesktopAppDirs.dataDir(),
    private val distBase: String = System.getenv(ENV_DIST_BASE)?.takeIf { it.isNotBlank() } ?: DEFAULT_DIST_BASE,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun ensure(onProgress: (String) -> Unit): NodeResult = withContext(ioDispatcher) {
        DesktopJobRuntimeEnv.nodeBinDir(baseDir)?.let {
            logger("using previously provisioned Node at ${it.path}")
            return@withContext NodeResult.Available(NodeRuntime(it))
        }
        onProgress("Checking for Node.js…")
        if (systemNodeAndNpm()) {
            logger("system Node.js + npm found on PATH")
            return@withContext NodeResult.Available(NodeRuntime(null))
        }
        val asset = assetForHost()
            ?: return@withContext NodeResult.Failed("No Node.js build is available for this operating system / architecture.")

        val tmp = File(baseDir, "runtimes/.node-download").apply { mkdirs() }
        val archive = File(tmp, asset)
        try {
            onProgress("Downloading Node.js…")
            val url = "$distBase/$NODE_VERSION/$asset"
            logger("downloading Node.js from $url")
            download(url, archive)

            onProgress("Installing Node.js…")
            val extractDir = File(tmp, "extract").apply { deleteRecursively(); mkdirs() }
            extract(archive, extractDir)
            val versioned = extractDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("node-") }
                ?: return@withContext NodeResult.Failed("Node.js archive had an unexpected layout.")

            val dest = DesktopJobRuntimeEnv.nodeRoot(baseDir)
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!versioned.renameTo(dest)) {
                versioned.copyRecursively(dest, overwrite = true)
                versioned.deleteRecursively()
            }
            val binDir = DesktopJobRuntimeEnv.nodeBinDir(baseDir)
                ?: return@withContext NodeResult.Failed("Node.js installed but its `node` binary wasn't found.")
            if (!verify(binDir)) {
                return@withContext NodeResult.Failed("The downloaded Node.js failed to run on this machine.")
            }
            logger("provisioned private Node.js at ${binDir.path}")
            NodeResult.Available(NodeRuntime(binDir))
        } catch (t: Throwable) {
            NodeResult.Failed("Couldn't install Node.js: ${t.message}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun systemNodeAndNpm(): Boolean = runQuiet("node --version") && runQuiet("npm --version")

    private fun verify(binDir: File): Boolean = runCatching {
        val node = File(binDir, if (isWindows) "node.exe" else "node")
        val proc = ProcessBuilder(node.absolutePath, "--version").redirectErrorStream(true).start()
        val ok = proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) proc.destroyForcibly()
        ok
    }.getOrDefault(false)

    /** Run a shell command quietly; true iff it exits 0 within a short timeout. */
    private fun runQuiet(command: String): Boolean = runCatching {
        val argv = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        val proc = ProcessBuilder(argv).redirectErrorStream(true).start()
        proc.inputStream.readBytes() // drain so the pipe never blocks
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); false } else proc.exitValue() == 0
    }.getOrDefault(false)

    private fun download(url: String, dest: File) {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        check(dest.length() > 0L) { "downloaded Node.js archive is empty" }
    }

    /** Extract via the system `tar` (GNU on Linux, bsdtar on macOS/Windows 10+ — handles gz + zip). */
    private fun extract(archive: File, targetDir: File) {
        targetDir.mkdirs()
        val proc = ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", targetDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "tar extract failed (exit=$code): ${out.take(500)}" }
    }

    private fun assetForHost(): String? {
        val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return when {
            isWindows -> if (arch == "x64") "node-$NODE_VERSION-win-x64.zip" else null
            isMac -> "node-$NODE_VERSION-darwin-$arch.tar.gz"
            else -> "node-$NODE_VERSION-linux-$arch.tar.gz"
        }
    }

    private val isWindows: Boolean get() = osName.contains("win")
    private val isMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")
    private val osName: String get() = System.getProperty("os.name").orEmpty().lowercase()

    companion object {
        const val NODE_VERSION = "v20.18.1"
        const val ENV_DIST_BASE = "LOCALAGENT_NODE_DIST_BASE"
        const val DEFAULT_DIST_BASE = "https://nodejs.org/dist"
    }
}
