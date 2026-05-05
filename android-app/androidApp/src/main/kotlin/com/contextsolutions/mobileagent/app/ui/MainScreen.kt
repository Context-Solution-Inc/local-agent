package com.contextsolutions.mobileagent.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.contextsolutions.mobileagent.app.ui.chat.ChatScreen
import com.contextsolutions.mobileagent.app.ui.download.DownloadScreen

/**
 * Top-level Composable hosted by [com.contextsolutions.mobileagent.app.MainActivity].
 * Decides between download and chat based on [MainViewModel.modelPresent].
 *
 * Compose Navigation is intentionally not used — there are exactly two states
 * and the transition is data-driven (model file exists or doesn't), not a
 * navigation stack.
 */
@Composable
fun MainScreen(
    onOpenSpike: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val modelPresent by viewModel.modelPresent.collectAsState()
    if (modelPresent) {
        ChatScreen(onOpenSpike = onOpenSpike)
    } else {
        DownloadScreen()
    }
}
