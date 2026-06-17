package com.contextsolutions.localagent.job

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopJobCatalogTest {

    /** A library store whose dir() points at a hand-populated folder (no extract). */
    private fun libraryAt(base: File) =
        DesktopJobLibraryStore(deploymentId = "test", baseDir = base, resourceOpener = { null })

    private fun writeJob(libDir: File, relPath: String, json: String): File {
        val file = File(libDir, relPath)
        file.parentFile.mkdirs()
        file.writeText(json)
        return file
    }

    @Test
    fun listsJobsWithProgramResolvedForThisOs() = runBlocking {
        val base = createTempDirectory("catalog").toFile()
        try {
            val libDir = File(base, DesktopJobLibraryStore.DIR_NAME).apply { mkdirs() }
            writeJob(
                libDir,
                "property-search/ca/job.settings.json",
                """
                {
                  "version": 1,
                  "name": "Property Search (Canada)",
                  "description": "Watch realtor.ca for new listings.",
                  "program": { "linux": "watch.sh", "macos": "watch.sh", "windows": "watch.cmd" },
                  "init": { "steps": [ { "id": "x", "run": { "linux": "true", "macos": "true", "windows": "exit 0" } } ] }
                }
                """.trimIndent(),
            )
            File(libDir, "job.list.json").writeText(
                """{"version":1,"jobs":[{"name":"property-search-ca","path":"property-search/ca/job.settings.json"}]}""",
            )

            val entries = DesktopJobCatalog(libraryAt(base)).list()
            assertEquals(1, entries.size)
            val e = entries.single()
            assertEquals("property-search-ca", e.id)
            assertEquals("Property Search (Canada)", e.displayName)
            assertEquals("Watch realtor.ca for new listings.", e.description)
            assertTrue(e.supportedOnThisOs, "manifest has a program for every OS")
            assertTrue(e.programPath.endsWith("watch.sh") || e.programPath.endsWith("watch.cmd"), "resolved program: ${e.programPath}")
            assertTrue(e.workingDir.endsWith("property-search/ca".replace('/', File.separatorChar)))
            assertTrue(e.requiresInit)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun skipsEntriesWithMissingManifest() = runBlocking {
        val base = createTempDirectory("catalog").toFile()
        try {
            val libDir = File(base, DesktopJobLibraryStore.DIR_NAME).apply { mkdirs() }
            File(libDir, "job.list.json").writeText(
                """{"version":1,"jobs":[{"name":"ghost","path":"does/not/exist/job.settings.json"}]}""",
            )
            assertTrue(DesktopJobCatalog(libraryAt(base)).list().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun unavailableWhenNoProgramForAnyOs() = runBlocking {
        val base = createTempDirectory("catalog").toFile()
        try {
            val libDir = File(base, DesktopJobLibraryStore.DIR_NAME).apply { mkdirs() }
            writeJob(libDir, "foo/job.settings.json", """{"version":1,"name":"Foo","program":{}}""")
            File(libDir, "job.list.json").writeText(
                """{"version":1,"jobs":[{"name":"foo","path":"foo/job.settings.json"}]}""",
            )
            val e = DesktopJobCatalog(libraryAt(base)).list().single()
            assertEquals(false, e.supportedOnThisOs)
            assertEquals("", e.programPath)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun emptyWhenNoListFile() = runBlocking {
        val base = createTempDirectory("catalog").toFile()
        try {
            File(base, DesktopJobLibraryStore.DIR_NAME).mkdirs()
            assertTrue(DesktopJobCatalog(libraryAt(base)).list().isEmpty())
        } finally {
            base.deleteRecursively()
        }
    }
}
