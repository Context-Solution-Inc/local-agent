package com.contextsolutions.localagent.job

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopToolPreflightTest {

    @Test
    fun resolveExecutableFindsOnPath() {
        val dir = createTempDirectory("preflight").toFile()
        try {
            val tool = File(dir, "mytool").apply { writeText("#!/bin/sh\n") }
            val env = mapOf("PATH" to dir.absolutePath)
            assertEquals(tool, DesktopToolPreflight.resolveExecutable("mytool", env = env, isWindows = false))
            assertNull(DesktopToolPreflight.resolveExecutable("nope", env = env, isWindows = false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveExecutableHonoursExtraDirsAndPaths() {
        val dir = createTempDirectory("preflight").toFile()
        try {
            val tool = File(dir, "node").apply { writeText("x") }
            val resolved = DesktopToolPreflight.resolveExecutable(
                "node", extraDirs = listOf(dir), env = mapOf("PATH" to "/no/such/dir"), isWindows = false,
            )
            assertEquals(tool, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveExecutableAcceptsAbsolutePath() {
        val dir = createTempDirectory("preflight").toFile()
        try {
            val tool = File(dir, "chrome").apply { writeText("x") }
            assertEquals(tool, DesktopToolPreflight.resolveExecutable(tool.absolutePath, isWindows = false))
            assertNull(DesktopToolPreflight.resolveExecutable(File(dir, "gone").absolutePath, isWindows = false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun leadingExecutableParsesForms() {
        assertEquals("google-chrome", DesktopToolPreflight.leadingExecutable("google-chrome --foo bar", false))
        assertEquals(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            DesktopToolPreflight.leadingExecutable(
                "\"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome\" --x", false,
            ),
        )
        assertEquals(
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            DesktopToolPreflight.leadingExecutable(
                "& \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" --x", true,
            ),
        )
        assertNull(DesktopToolPreflight.leadingExecutable("   ", false))
        assertNull(DesktopToolPreflight.leadingExecutable("VAR=1 node script.js", false))
    }

    @Test
    fun detectBrowserLinux() {
        val dir = createTempDirectory("preflight").toFile()
        try {
            File(dir, "google-chrome").apply { writeText("x") }
            val env = mapOf("PATH" to dir.absolutePath)
            assertTrue(DesktopToolPreflight.detectBrowser(env = env, isWindows = false, isMac = false) != null)
            assertNull(
                DesktopToolPreflight.detectBrowser(env = mapOf("PATH" to "/no/such"), isWindows = false, isMac = false),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun detectBrowserWindowsAndMacUseKnownPaths() {
        val winFound = DesktopToolPreflight.detectBrowser(
            env = mapOf("ProgramFiles" to "C:\\Program Files"),
            isWindows = true, isMac = false,
            fileExists = { it.path.endsWith("chrome.exe") },
        )
        assertTrue(winFound != null && winFound.path.endsWith("chrome.exe"))

        val macFound = DesktopToolPreflight.detectBrowser(
            env = emptyMap(), isWindows = false, isMac = true,
            fileExists = { it.path.contains("Google Chrome.app") },
        )
        assertTrue(macFound != null && macFound.path.contains("Google Chrome.app"))
    }

    @Test
    fun extractMissingProgramRecognizesPatterns() {
        assertEquals("google-chrome", DesktopToolPreflight.extractMissingProgram("sh: 1: google-chrome: not found"))
        assertEquals("google-chrome", DesktopToolPreflight.extractMissingProgram("sh: google-chrome: command not found"))
        assertEquals("tar", DesktopToolPreflight.extractMissingProgram("Cannot run program \"tar\": error=2"))
        assertNull(DesktopToolPreflight.extractMissingProgram("some unrelated output"))
    }

    @Test
    fun friendlyToolMessageIsActionable() {
        assertTrue(DesktopToolPreflight.friendlyToolMessage("google-chrome").contains("Chrome"))
        assertTrue(DesktopToolPreflight.friendlyToolMessage("tar").contains("tar"))
        assertTrue(DesktopToolPreflight.friendlyToolMessage("node").contains("nodejs.org"))
        assertTrue(DesktopToolPreflight.friendlyToolMessage("ripgrep").contains("ripgrep"))
    }
}
