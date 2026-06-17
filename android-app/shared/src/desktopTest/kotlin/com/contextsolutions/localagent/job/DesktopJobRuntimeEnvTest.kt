package com.contextsolutions.localagent.job

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopJobRuntimeEnvTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun noProvisionedNodeMeansNoExtraPath() {
        val base = createTempDirectory("rtenv").toFile()
        try {
            assertNull(DesktopJobRuntimeEnv.nodeBinDir(base))
            assertTrue(DesktopJobRuntimeEnv.extraPathEntries(base).isEmpty())
            val env = mutableMapOf("PATH" to "/usr/bin")
            DesktopJobRuntimeEnv.applyTo(env, base)
            assertEquals("/usr/bin", env["PATH"], "PATH untouched when nothing is provisioned")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun provisionedNodeIsPrependedToPath() {
        if (isWindows) return // posix layout (<root>/bin/node) under test
        val base = createTempDirectory("rtenv").toFile()
        try {
            val bin = File(DesktopJobRuntimeEnv.nodeRoot(base), "bin").apply { mkdirs() }
            File(bin, "node").writeText("#!/bin/sh\n")
            assertEquals(bin.absolutePath, DesktopJobRuntimeEnv.nodeBinDir(base)?.absolutePath)
            val env = mutableMapOf("PATH" to "/usr/bin")
            DesktopJobRuntimeEnv.applyTo(env, base)
            assertEquals("${bin.absolutePath}${File.pathSeparator}/usr/bin", env["PATH"])
        } finally {
            base.deleteRecursively()
        }
    }
}
