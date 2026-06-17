package com.contextsolutions.localagent.job

/**
 * One job offered by the desktop **Choose Job** catalog (PR #100), derived from the
 * bundled `agent-jobs` library: an entry in `job.list.json` joined with the
 * `job.settings.json` manifest it points at.
 *
 * @property id the `job.list.json` entry name (stable key, e.g. `property-search-ca`).
 * @property displayName the manifest `name` shown to the user.
 * @property description the manifest `description` shown to the user.
 * @property programPath OS-resolved program for THIS host (fills the form's Job
 *   Location / `command`). Blank when the job has no program for this OS.
 * @property workingDir the manifest's folder (the subprocess working dir).
 * @property supportedOnThisOs false when the manifest has no `program` entry for the
 *   host OS — the row is shown disabled.
 * @property requiresInit true when the manifest declares an `init` block.
 */
data class JobCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val programPath: String,
    val workingDir: String,
    val supportedOnThisOs: Boolean,
    val requiresInit: Boolean,
)

/**
 * Reads the bundled job catalog. Desktop-only ([DesktopJobCatalog]); unbound on
 * mobile/iOS, where the shared `JobsViewModel` resolves it via `getOrNull()` and
 * hides the Choose Job button (jobs are defined only on the trusted desktop).
 */
interface JobCatalog {
    /** All jobs listed in `job.list.json`, skipping entries whose manifest is missing/unreadable. */
    suspend fun list(): List<JobCatalogEntry>
}
