package com.contextsolutions.localagent.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.contextsolutions.localagent.memory.BackupReader
import com.contextsolutions.localagent.memory.BackupWriter

/**
 * iOS actual (PR #41): no-op stub. Memory backup export/import is deferred this
 * milestone (a `UIDocumentPicker`-backed file picker is a follow-up); both
 * launchers report cancelled.
 */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker = remember {
    object : BackupFilePicker {
        override fun launchExport(suggestedName: String, onPicked: (BackupWriter?) -> Unit) = onPicked(null)
        override fun launchImport(onPicked: (BackupReader?) -> Unit) = onPicked(null)
    }
}
