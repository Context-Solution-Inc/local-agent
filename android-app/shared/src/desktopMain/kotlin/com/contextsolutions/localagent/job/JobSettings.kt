package com.contextsolutions.localagent.job

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A `job.settings.json` manifest that lives inside a job's program folder (PR #86).
 * It lets the user pick a *folder* rather than guess the right OS-specific entry
 * point, and it carries hidden CLI args plus program-defined settings:
 *
 * ```json
 * {
 *   "version": 1,
 *   "name": "Property Search (Canada)",
 *   "program": { "linux": "watch.sh", "macos": "watch.sh", "windows": "watch.cmd" },
 *   "args": ["--headless"],
 *   "settings": { "...": "program-defined, read by the program from its own cwd" }
 * }
 * ```
 *
 * - [program] maps an OS key (`linux`/`macos`/`windows`) → the entry point,
 *   resolved relative to the manifest's folder (an absolute path also works).
 *   The desktop Choose Job catalog resolves this to fill the "Job Command" field.
 * - [args] are passed to the program on every run but never shown in the UI and
 *   never collide with the user-typed keyword(s); [JobExecutor] reads them live.
 * - [settings] is opaque to us — the program reads it from its own working
 *   directory. We deliberately don't interpret it.
 * - [init] (PR #100, optional) declares one-time setup the desktop runs BEFORE a
 *   chosen job can be saved (e.g. `npm install`, warming a browser profile). See
 *   [JobInitSpec]. Absent ⇒ the job needs no initialization.
 */
@Serializable
data class JobSettings(
    val version: Int = 1,
    val name: String? = null,
    val description: String? = null,
    val program: Map<String, String> = emptyMap(),
    val args: List<String> = emptyList(),
    val settings: JsonElement? = null,
    val init: JobInitSpec? = null,
)

/**
 * One-time initialization for a job (PR #100). Runs when the user picks the job
 * from the desktop catalog, before the job is saved; if any step or [verify]
 * fails the job is NOT saved and the user is told why.
 *
 * @property requires runtimes the job needs provisioned before its steps run (currently
 *   only `"node"` — checks the system, installs a private Node into app-data if missing).
 * @property steps ordered setup steps, each either non-interactive ([JobInitStep.run])
 *   or interactive ([JobInitStep.launch]).
 * @property verify optional OS-keyed command run last; a non-zero exit means init
 *   failed (e.g. `test -d "$HOME/.realtor-chrome"`).
 */
@Serializable
data class JobInitSpec(
    val requires: List<String> = emptyList(),
    val steps: List<JobInitStep> = emptyList(),
    val verify: Map<String, String> = emptyMap(),
)

/**
 * A single init step (PR #100). Exactly one of [run] / [launch] should be set:
 *
 * - [run] — a non-interactive command (OS-keyed). Run to completion; a non-zero
 *   exit fails initialization.
 * - [launch] + `interactive = true` — an interactive command (OS-keyed): the UI
 *   shows [instructions], the command is started (e.g. a real Chrome window), and
 *   the desktop waits for the user to finish and close it before continuing.
 *
 * Commands are full shell strings (run via `sh -c` / `powershell -Command`) — the
 * manifest is trusted bundled content, so unlike the user's keyword arg in
 * [JobExecutor] there's no injection-safe positional binding. A step with no entry
 * for the host OS is skipped (not a failure).
 */
@Serializable
data class JobInitStep(
    val id: String? = null,
    val title: String? = null,
    val interactive: Boolean = false,
    val instructions: String? = null,
    val run: Map<String, String> = emptyMap(),
    val launch: Map<String, String> = emptyMap(),
)

object JobSettingsLoader {
    const val FILE_NAME = "job.settings.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Load the manifest. [path] may be the job folder OR the `job.settings.json`
     * file itself; returns null if the file is absent or unparseable.
     */
    fun load(path: File): JobSettings? = runCatching {
        val file = if (path.isDirectory) File(path, FILE_NAME) else path
        if (file.isFile) json.decodeFromString<JobSettings>(file.readText()) else null
    }.getOrNull()

    /** True iff [dir] is a directory containing a `job.settings.json` file. */
    fun hasManifest(dir: File): Boolean = dir.isDirectory && File(dir, FILE_NAME).isFile

    /**
     * Resolve the program entry point for the current OS, relative to [jobDir].
     * Returns null when there's no entry for this OS. `File(jobDir, rel)` returns
     * the absolute path unchanged if the manifest value is already absolute.
     */
    fun resolveProgram(settings: JobSettings, jobDir: File): File? =
        currentOsKey()
            ?.let { settings.program[it] }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(jobDir, it) }

    /** `linux` / `macos` / `windows`, or null on an unrecognized OS. */
    fun currentOsKey(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> "linux"
            else -> null
        }
    }
}
