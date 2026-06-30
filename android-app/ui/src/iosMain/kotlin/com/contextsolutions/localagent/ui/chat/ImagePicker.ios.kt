package com.contextsolutions.localagent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * iOS actual (PR #41): no-op stub. Image input is deferred on iOS this milestone
 * (the chat attach button stays inert); a real `PHPickerViewController`-backed
 * picker is a follow-up. `launch` always reports cancelled.
 */
@Composable
actual fun rememberImagePicker(): ImagePicker = remember {
    object : ImagePicker {
        override fun launch(onPicked: (ByteArray?) -> Unit) = onPicked(null)
    }
}
