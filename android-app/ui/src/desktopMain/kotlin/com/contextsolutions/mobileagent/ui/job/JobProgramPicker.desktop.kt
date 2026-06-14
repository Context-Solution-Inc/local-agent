package com.contextsolutions.mobileagent.ui.job

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.contextsolutions.mobileagent.job.JobSettingsLoader
import java.awt.Toolkit
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private const val NEEDS_MANIFEST = "Select a job directory that contains a ${JobSettingsLoader.FILE_NAME} file."

/**
 * Desktop actual: a Swing directory chooser (run on the EDT via `invokeAndWait`)
 * that only accepts a folder containing a `job.settings.json` manifest. It reads
 * the manifest, resolves the program for the current OS, and returns the resolved
 * path + folder. Returns `null` (callback) on cancel or an invalid manifest.
 */
@Composable
actual fun rememberJobProgramPicker(): JobProgramPicker = remember {
    object : JobProgramPicker {
        override fun launch(onPicked: (PickedJobProgram?) -> Unit) {
            onPicked(chooseJobProgram())
        }
    }
}

private fun chooseJobProgram(): PickedJobProgram? {
    var result: PickedJobProgram? = null
    val task = Runnable {
        // Enforce the manifest at selection time: a directory without
        // job.settings.json can't be confirmed (approveSelection refuses it).
        val chooser = object : JFileChooser() {
            override fun approveSelection() {
                val dir = selectedFile
                if (dir != null && JobSettingsLoader.hasManifest(dir)) {
                    super.approveSelection()
                } else {
                    Toolkit.getDefaultToolkit().beep()
                    JOptionPane.showMessageDialog(this, NEEDS_MANIFEST, "Choose a job folder", JOptionPane.WARNING_MESSAGE)
                }
            }
        }.apply {
            dialogTitle = "Choose a job folder (must contain ${JobSettingsLoader.FILE_NAME})"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            toolTipText = NEEDS_MANIFEST
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@Runnable

        val folder: File = chooser.selectedFile ?: return@Runnable
        val settings = JobSettingsLoader.load(folder)
        if (settings == null) {
            // Defensive — approveSelection already guaranteed the file exists.
            JOptionPane.showMessageDialog(null, "Couldn't read ${JobSettingsLoader.FILE_NAME} in ${folder.path}.", "Invalid job settings", JOptionPane.ERROR_MESSAGE)
            return@Runnable
        }
        val program = JobSettingsLoader.resolveProgram(settings, folder)
        if (program == null) {
            val os = JobSettingsLoader.currentOsKey() ?: "this OS"
            JOptionPane.showMessageDialog(null, "${JobSettingsLoader.FILE_NAME} has no program entry for $os.", "Unsupported OS", JOptionPane.ERROR_MESSAGE)
            return@Runnable
        }
        result = PickedJobProgram(programPath = program.absolutePath, workingDir = folder.absolutePath)
    }
    if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
    return result
}
