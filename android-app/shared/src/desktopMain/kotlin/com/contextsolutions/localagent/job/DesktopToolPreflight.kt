package com.contextsolutions.localagent.job

import java.io.File

/**
 * Pre-flight detection of external tools a job's init needs, plus friendly, actionable error
 * copy when one is missing (PR #101).
 *
 * The pure helpers ([resolveExecutable], [detectBrowser], [leadingExecutable],
 * [extractMissingProgram], [friendlyToolMessage]) take their OS / environment / filesystem as
 * injectable parameters so they're unit-testable without spawning a subprocess. The defaults
 * read the real host.
 *
 * Motivation: a job like property-search needs Node.js (auto-provisioned by
 * [DesktopNodeProvisioner]) AND a real browser for its interactive sign-in step. A missing
 * browser used to slip through — the `launch` command runs under `sh -c`, so `sh` itself starts
 * fine and exits 127, leaving the step falsely marked DONE — and other failures surfaced raw
 * exception text (`Cannot run program "tar"`, `command not found`). This catches those up front
 * and translates them.
 */
object DesktopToolPreflight {

    val currentIsWindows: Boolean get() = osName.contains("win")
    val currentIsMac: Boolean get() = osName.contains("mac") || osName.contains("darwin")
    private val osName: String get() = System.getProperty("os.name").orEmpty().lowercase()

    /**
     * Resolve [name] to an executable file, or null if not found. A [name] containing a path
     * separator is checked directly; a bare name is searched across [extraDirs] then the `PATH`
     * dirs from [env]. On Windows the usual executable extensions are tried.
     */
    fun resolveExecutable(
        name: String,
        extraDirs: List<File> = emptyList(),
        env: Map<String, String> = System.getenv(),
        isWindows: Boolean = currentIsWindows,
        fileExists: (File) -> Boolean = { it.isFile },
    ): File? {
        if (name.isBlank()) return null
        val exts = if (isWindows) windowsExts(env) else listOf("")
        fun match(base: File): File? =
            exts.asSequence().map { ext -> File(base.path + ext) }.firstOrNull(fileExists)

        if (name.contains('/') || name.contains('\\')) return match(File(name))

        val pathDirs = (env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value)
            .orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
        return (extraDirs + pathDirs).asSequence()
            .mapNotNull { dir -> match(File(dir, name)) }
            .firstOrNull()
    }

    /** Locate an installed Chrome / Chromium / Edge, or null if none is found. */
    fun detectBrowser(
        env: Map<String, String> = System.getenv(),
        isWindows: Boolean = currentIsWindows,
        isMac: Boolean = currentIsMac,
        fileExists: (File) -> Boolean = { it.isFile },
    ): File? {
        when {
            isMac -> {
                val home = env["HOME"].orEmpty()
                val candidates = listOf(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                ) + if (home.isNotBlank()) listOf(
                    "$home/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                ) else emptyList()
                return candidates.map(::File).firstOrNull(fileExists)
            }
            isWindows -> {
                val roots = listOfNotNull(
                    env["ProgramFiles"], env["ProgramFiles(x86)"], env["LOCALAPPDATA"],
                ).filter { it.isNotBlank() }
                val rel = listOf(
                    "Google\\Chrome\\Application\\chrome.exe",
                    "Chromium\\Application\\chrome.exe",
                    "Microsoft\\Edge\\Application\\msedge.exe",
                )
                return roots.asSequence()
                    .flatMap { root -> rel.asSequence().map { File("$root\\$it") } }
                    .firstOrNull(fileExists)
            }
            else -> {
                val names = listOf(
                    "google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                    "microsoft-edge", "microsoft-edge-stable",
                )
                return names.asSequence()
                    .mapNotNull { resolveExecutable(it, env = env, isWindows = false, fileExists = fileExists) }
                    .firstOrNull()
            }
        }
    }

    /**
     * Best-effort first executable token of a shell command, honoring a leading PowerShell `&`
     * and double-quoted paths. Returns null when it can't confidently parse (a POSIX
     * `VAR=value cmd` env prefix, or an empty command) — callers then launch-and-trust.
     */
    fun leadingExecutable(command: String, isWindows: Boolean): String? {
        var s = command.trim()
        if (isWindows && s.startsWith("&")) s = s.removePrefix("&").trimStart()
        if (s.isEmpty()) return null
        val token = if (s.startsWith("\"")) {
            val end = s.indexOf('"', startIndex = 1)
            if (end <= 0) return null
            s.substring(1, end)
        } else {
            s.takeWhile { !it.isWhitespace() }
        }
        if (token.isBlank()) return null
        // POSIX env-assignment prefix (e.g. `FOO=bar cmd`) — not the executable; give up.
        if (!token.contains('/') && !token.contains('\\') && token.contains('=')) return null
        return token
    }

    /** Pull the missing program's name out of subprocess output, or null if not recognized. */
    fun extractMissingProgram(output: String): String? {
        Regex("""Cannot run program "([^"]+)"""").find(output)?.let { return baseName(it.groupValues[1]) }
        Regex("""([\w.+\-]+): (?:command not found|not found)""").find(output)?.let { return it.groupValues[1] }
        Regex("""([\w.+\-/\\ ]+): No such file or directory""").find(output)
            ?.let { return baseName(it.groupValues[1].trim()) }
        return null
    }

    /** A clear, actionable message for a missing [program] (a tool name or path). */
    fun friendlyToolMessage(program: String, rawOutput: String = ""): String {
        val p = baseName(program).lowercase()
        return when {
            "chrome" in p || "chromium" in p || "edge" in p || p == "browser" ->
                "Google Chrome (or Chromium) is required for this job but wasn't found. " +
                    "Install it from https://www.google.com/chrome/ and choose the job again."
            p == "tar" ->
                "The `tar` utility is required to unpack Node.js but wasn't found on your system."
            "node" in p || "npm" in p ->
                "Node.js is required for this job but couldn't be set up. " +
                    "Install Node.js from https://nodejs.org/ and choose the job again."
            else -> {
                val what = if (program.isBlank()) "a required program" else "`${baseName(program)}`"
                "This job needs $what, but it wasn't found on your system. " +
                    "Install it and choose the job again."
            }
        }
    }

    private fun baseName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    private fun windowsExts(env: Map<String, String>): List<String> {
        val pathext = env.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value
            ?.split(';')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: listOf(".COM", ".EXE", ".BAT", ".CMD")
        // Try a bare name too (in case it already carries an extension).
        return listOf("") + pathext
    }
}
