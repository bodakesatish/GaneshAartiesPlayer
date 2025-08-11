package com.bodakesatish.ganeshaarties

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import com.bodakesatish.ganeshaarties.R // Import your R file
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

// PlayerUiState data class (same as before)
data class PlayerUiState(
    val currentAarti: AartiItem? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val totalDuration: Long = 0L,
    val playlist: List<AartiItem> = emptyList() // The currently selected (checked) aarties
)

class AartiViewModel : ViewModel() {

    // Use StateFlow for observing in Activity
    private val _aartiesStateFlow = MutableStateFlow<List<AartiItem>>(emptyList())
    val aartiesStateFlow: StateFlow<List<AartiItem>> = _aartiesStateFlow.asStateFlow()

    private val _playerUiState = MutableStateFlow(PlayerUiState())
    val playerUiState: StateFlow<PlayerUiState> = _playerUiState.asStateFlow()

    private var progressUpdateJob: Job? = null

    init {
        loadAarties()
    }

    private fun loadAarties() {
        _aartiesStateFlow.value = listOf(
            AartiItem(1, "sukhakarta_dukhaharta", R.raw.sukhakarta_dukhaharta, isChecked = false),
            AartiItem(2, "durge_durghat_bhari", R.raw.durge_durghat_bhari, isChecked = false),
            AartiItem(3, "lavthavati_vikrala", R.raw.lavthavati_vikrala, isChecked = false),
            AartiItem(4, "yuge_atthavis_vitthala", R.raw.yuge_atthavis_vitthala, isChecked = false),
            AartiItem(5, "datta_aarti", R.raw.datta_aarti, isChecked = false),
            AartiItem(6, "ghalin_lotangan_vandin_charan", R.raw.ghalin_lotangan_vandin_charan, isChecked = false)
        )//.onEach { it.isChecked = true } // Default to checked
        updatePlayerPlaylist()
    }

    fun onAartiCheckedChanged(aartiItem: AartiItem, isChecked: Boolean) {
        _aartiesStateFlow.update { currentList ->
            currentList.map {
                if (it.id == aartiItem.id) {
                    it.copy(isChecked = isChecked)
                } else {
                    it
                }
            }
        }
        updatePlayerPlaylist()
    }

    fun moveAarti(fromPosition: Int, toPosition: Int) {
        _aartiesStateFlow.update { currentList ->
            val mutableList = currentList.toMutableList()
            if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                Collections.swap(mutableList, fromPosition, toPosition)
            }
            mutableList.toList()
        }
        updatePlayerPlaylist()
    }

    private fun updatePlayerPlaylist() {
        val checkedAarties = _aartiesStateFlow.value.filter { it.isChecked }
        _playerUiState.update { it.copy(playlist = checkedAarties) }
        // The Activity will observe aartiesStateFlow and tell the service to update its playlist.
    }

    // Callbacks from MediaController listener in Activity will update this
    fun updatePlaybackState(
        isPlaying: Boolean,
        currentAartiId: Int?,
        position: Long,
        duration: Long
    ) {
        val currentAarti = _aartiesStateFlow.value.find { it.id == currentAartiId }
        _playerUiState.update {
            it.copy(
                isPlaying = isPlaying,
                currentAarti = currentAarti,
                // Use the position directly from the MediaController event first
                currentPosition = position.coerceAtLeast(0L),
                totalDuration = duration.coerceAtLeast(0L)
            )
        }

        if (isPlaying) {
            startProgressUpdates()
        } else {
            stopProgressUpdates()
            // When stopping, ensure the last known position is emitted if it wasn't the final one
            // This is often handled by the final STATE_ENDED or onIsPlayingChanged(false)
            _playerUiState.update {
                it.copy(currentPosition = position.coerceAtLeast(0L))
            }
        }
    }

    fun handlePlaylistEnded() {
        // Uncheck all aarties
        _aartiesStateFlow.update { currentList ->
            currentList.map { it.copy(isChecked = false) }
        }
        // Reset player UI state
        _playerUiState.update {
            it.copy(
                currentAarti = null,
                isPlaying = false,
                currentPosition = 0L,
                totalDuration = 0L, // Reset total duration as well
                playlist = emptyList() // Clear the UI state's playlist
            )
        }
        // The change in _aartiesStateFlow will trigger the observer in MainActivity,
        // which will call updateServicePlaylist() with an empty list, effectively clearing
        // the MediaController's playlist.
    }


    private fun startProgressUpdates() {
        stopProgressUpdates() // Ensure only one job is running
        progressUpdateJob = viewModelScope.launch {
            while (isActive && _playerUiState.value.isPlaying) { // Check coroutine isActive and playback state
                // This will re-trigger the playerUiState.collect in MainActivity
                // but with the *same* position unless we get it from the controller.
                // The crucial part is that the Player.Listener in MainActivity needs to
                // *also* call updatePlaybackState when onPositionDiscontinuity occurs
                // or periodically if the events are not frequent enough.

                // For a simple polling approach directly in ViewModel (if you don't have direct controller access here):
                // This assumes your ViewModel would somehow get the latest position.
                // However, the Activity's Player.Listener is better for this.
                // What this loop does is ensure the UI *re-collects* the state,
                // which is useful if other things cause the UI to need a refresh
                // even if position didn't change (less common for just seekbar).

                // The primary driver for position updates should be the Player.Listener in MainActivity.
                // This loop here is more of a failsafe or for scenarios where you might
                // have other time-dependent UI elements in the ViewModel.
                // For just the seekbar, the Player.Listener's onPositionDiscontinuity
                // and a periodic update from the Activity are more direct.

                // Let's refine: The ViewModel's job is to hold state.
                // The Activity's Player.Listener is the source of truth for player events.
                // The Activity can have its own poller if Player.Listener events aren't enough.

                // So, remove this viewModelScope.launch for progress from ViewModel.
                // It's better handled in MainActivity where the MediaController is.
                delay(1000) // Keep the delay if you had other reasons for this loop.
                // But for seekbar, focus on MainActivity's listener.
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressUpdates()
    }

}
