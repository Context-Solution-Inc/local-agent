package com.contextsolutions.localagent.job

import com.contextsolutions.localagent.inference.DesktopAppDirs
import java.io.File

/**
 * Locally provisioned runtimes that job subprocesses need on their `PATH` (PR #100).
 *
 * When [DesktopNodeProvisioner] installs a private Node (because the system has none), it
 * lands at a stable `<app-data>/runtimes/node`. This object exposes that bin dir so BOTH
 * the initializer (running `npm install`) and [JobExecutor] (running the job later) prepend
 * it to the subprocess `PATH` — without it, a scheduled run would fail to find `node`/`npm`
 * even though setup succeeded. A no-op when no private runtime was provisioned (the system's
 * own `node` is used).
 */
object DesktopJobRuntimeEnv {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Stable install root for the private Node runtime. */
    fun nodeRoot(baseDir: File = DesktopAppDirs.dataDir()): File = File(baseDir, "runtimes/node")

    /** The private Node bin dir, or null if not provisioned. Windows keeps `node.exe` at the root. */
    fun nodeBinDir(baseDir: File = DesktopAppDirs.dataDir()): File? {
        val root = nodeRoot(baseDir)
        val bin = if (isWindows) root else File(root, "bin")
        val exe = File(bin, if (isWindows) "node.exe" else "node")
        return bin.takeIf { exe.isFile }
    }

    /** PATH entries to prepend for job subprocesses (the private Node bin dir, if any). */
    fun extraPathEntries(baseDir: File = DesktopAppDirs.dataDir()): List<String> =
        listOfNotNull(nodeBinDir(baseDir)?.absolutePath)

    /**
     * Prepend [extraPathEntries] onto the `PATH` of a [ProcessBuilder]'s environment (mutated
     * in place). Matches the OS's PATH var name + separator. Best-effort.
     */
    fun applyTo(env: MutableMap<String, String>, baseDir: File = DesktopAppDirs.dataDir()) {
        val entries = extraPathEntries(baseDir)
        if (entries.isEmpty()) return
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val sep = File.pathSeparator
        val existing = env[key].orEmpty()
        env[key] = (entries + existing.takeIf { it.isNotBlank() }.let { if (it != null) listOf(it) else emptyList() }).joinToString(sep)
    }
}
