package com.contextsolutions.localagent.job

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `job.list.json` — the index of jobs the bundled library offers (PR #100). */
@Serializable
internal data class JobList(
    val version: Int = 1,
    val jobs: List<JobListEntry> = emptyList(),
)

@Serializable
internal data class JobListEntry(
    val name: String,
    val path: String,
)

/**
 * Desktop [JobCatalog] (PR #100): reads `<library>/job.list.json` and joins each
 * entry with its `job.settings.json` manifest (via [JobSettingsLoader]) to build the
 * list the Choose Job dialog renders. The library is extracted by
 * [DesktopJobLibraryStore]; entries whose manifest is missing/unreadable are skipped.
 */
class DesktopJobCatalog(
    private val library: DesktopJobLibraryStore,
    private val logger: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : JobCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun list(): List<JobCatalogEntry> = withContext(ioDispatcher) {
        val root = library.dir()
        val listFile = File(root, LIST_FILE_NAME)
        if (!listFile.isFile) {
            logger("no $LIST_FILE_NAME under ${root.path}")
            return@withContext emptyList()
        }
        val list = runCatching { json.decodeFromString<JobList>(listFile.readText()) }
            .getOrElse {
                logger("failed to parse $LIST_FILE_NAME: ${it.message}")
                return@withContext emptyList()
            }
        list.jobs.mapNotNull { entry -> toEntry(root, entry) }
    }

    private fun toEntry(root: File, entry: JobListEntry): JobCatalogEntry? {
        val manifestFile = File(root, entry.path)
        val settings = JobSettingsLoader.load(manifestFile)
        if (settings == null) {
            logger("skipping '${entry.name}': no readable manifest at ${entry.path}")
            return null
        }
        val workingDir = manifestFile.parentFile ?: root
        val program = JobSettingsLoader.resolveProgram(settings, workingDir)
        return JobCatalogEntry(
            id = entry.name,
            displayName = settings.name?.takeIf { it.isNotBlank() } ?: entry.name,
            description = settings.description.orEmpty(),
            programPath = program?.absolutePath.orEmpty(),
            workingDir = workingDir.absolutePath,
            supportedOnThisOs = program != null,
            requiresInit = settings.init?.steps?.isNotEmpty() == true,
        )
    }

    companion object {
        const val LIST_FILE_NAME = "job.list.json"
    }
}
