package com.contextsolutions.localagent.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.memory.BackupReader
import com.contextsolutions.localagent.memory.BackupWriter
import com.contextsolutions.localagent.ui.i18n.tr
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop actual: Swing [JFileChooser] save/open dialogs (run on the EDT via
 * `invokeAndWait`). The picked [File] is wrapped as a [BackupWriter]/
 * [BackupReader]. Returns `null` (callback) when the user cancels.
 */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker {
    val filterDesc = tr(StringKeys.DESKTOP_BACKUP_FILTER_DESC)
    return remember(filterDesc) {
        object : BackupFilePicker {
            override fun launchExport(suggestedName: String, onPicked: (BackupWriter?) -> Unit) {
                val file = chooseFile(save = true, suggestedName = suggestedName, filterDesc = filterDesc)
                onPicked(file?.let { FileBackupWriter(it) })
            }

            override fun launchImport(onPicked: (BackupReader?) -> Unit) {
                val file = chooseFile(save = false, suggestedName = null, filterDesc = filterDesc)
                onPicked(file?.let { FileBackupReader(it) })
            }
        }
    }
}

private fun chooseFile(save: Boolean, suggestedName: String?, filterDesc: String): File? {
    var result: File? = null
    val task = Runnable {
        val chooser = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter(filterDesc, "json")
            if (suggestedName != null) selectedFile = File(suggestedName)
        }
        val outcome = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        if (outcome == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}

private class FileBackupWriter(private val file: File) : BackupWriter {
    override suspend fun writeText(text: String) {
        val target = if (file.extension.isEmpty()) File(file.parentFile, "${file.name}.json") else file
        target.writeText(text, Charsets.UTF_8)
    }
}

private class FileBackupReader(private val file: File) : BackupReader {
    override suspend fun readText(): String = file.readText(Charsets.UTF_8)
}
