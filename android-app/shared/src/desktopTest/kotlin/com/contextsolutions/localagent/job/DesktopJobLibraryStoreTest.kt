package com.contextsolutions.localagent.job

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopJobLibraryStoreTest {

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun store(base: File, deploymentId: String, bytes: ByteArray) =
        DesktopJobLibraryStore(
            deploymentId = deploymentId,
            baseDir = base,
            resourceOpener = { ByteArrayInputStream(bytes) },
        )

    @Test
    fun extractsLibraryAndWritesStamp() {
        val base = createTempDirectory("joblib").toFile()
        try {
            val zip = zipOf(
                mapOf(
                    "job.list.json" to """{"version":1,"jobs":[]}""",
                    "property-search/ca/job.settings.json" to """{"version":1}""",
                ),
            )
            val ran = store(base, "v1", zip).ensure()
            assertTrue(ran, "first ensure() should extract")
            val dir = File(base, DesktopJobLibraryStore.DIR_NAME)
            assertTrue(File(dir, "job.list.json").isFile)
            assertTrue(File(dir, "property-search/ca/job.settings.json").isFile)
            assertEquals("v1", File(dir, DesktopJobLibraryStore.STAMP_NAME).readText().trim())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun sameDeploymentIsNoOp() {
        val base = createTempDirectory("joblib").toFile()
        try {
            val zip = zipOf(mapOf("job.list.json" to """{"version":1,"jobs":[]}"""))
            store(base, "v1", zip).ensure()
            val ranAgain = store(base, "v1", zip).ensure()
            assertFalse(ranAgain, "same deployment id should skip re-extract")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun overlayPreservesUserFilesAcrossDeployments() {
        val base = createTempDirectory("joblib").toFile()
        try {
            val v1 = zipOf(mapOf("property-search/ca/job.settings.json" to """{"version":1}"""))
            store(base, "v1", v1).ensure()

            // A user-generated file inside a job folder (not in the bundle).
            val dir = File(base, DesktopJobLibraryStore.DIR_NAME)
            val seen = File(dir, "property-search/ca/seen.json").apply { writeText("""{"M5H 1T1":[]}""") }

            // A new deployment re-extracts (overwriting tracked files) but must not wipe seen.json.
            val v2 = zipOf(mapOf("property-search/ca/job.settings.json" to """{"version":2}"""))
            val ran = store(base, "v2", v2).ensure()
            assertTrue(ran, "new deployment id should re-extract")
            assertTrue(seen.isFile, "user file must survive the overlay")
            assertEquals("""{"M5H 1T1":[]}""", seen.readText())
            assertTrue(
                File(dir, "property-search/ca/job.settings.json").readText().contains("\"version\":2"),
                "tracked file should be overwritten with the new deployment's copy",
            )
            assertEquals("v2", File(dir, DesktopJobLibraryStore.STAMP_NAME).readText().trim())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun restoresExecutableBitOnProgramScripts() {
        // ZipInputStream drops the unix +x bit; the store must restore it for program[os] / *.sh.
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val base = createTempDirectory("joblib").toFile()
        try {
            val zip = zipOf(
                mapOf(
                    "property-search/ca/job.settings.json" to
                        """{"version":1,"program":{"linux":"watch.sh","macos":"watch.sh","windows":"watch.cmd"}}""",
                    "property-search/ca/watch.sh" to "#!/usr/bin/env bash\necho hi\n",
                ),
            )
            store(base, "v1", zip).ensure()
            val script = File(base, "${DesktopJobLibraryStore.DIR_NAME}/property-search/ca/watch.sh")
            assertTrue(script.isFile)
            assertTrue(script.canExecute(), "extracted program script must be executable")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun missingResourceIsNoOp() {
        val base = createTempDirectory("joblib").toFile()
        try {
            val ran = DesktopJobLibraryStore(deploymentId = "v1", baseDir = base, resourceOpener = { null }).ensure()
            assertFalse(ran)
        } finally {
            base.deleteRecursively()
        }
    }
}
