package com.contextsolutions.mobileagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.app.service.ModelDownloadController
import com.contextsolutions.mobileagent.app.service.ModelInventory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Top-level routing decision: download flow or chat surface.
 *
 * The routing depends on (a) whether the model file is on disk, and (b) the
 * current download-controller state. We re-check (a) on every emission of (b),
 * which is cheap (a single file existence + size check) and gives us free
 * reactivity — when the Worker renames the partial to the final filename and
 * the controller emits Completed, our flow re-runs and isPresent flips to true.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val inventory: ModelInventory,
    controller: ModelDownloadController,
) : ViewModel() {

    val modelPresent: StateFlow<Boolean> = controller.state
        .map { inventory.isPresent() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inventory.isPresent())
}
