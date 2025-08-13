package com.bodakesatish.ganeshaarties

import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections

data class PlayerUiState(
    val currentAarti: AartiItem? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val currentPlaylist: List<AartiItem> = emptyList()
)

class AartiViewModel : ViewModel() {

    private val _aartiItemsFlow = MutableStateFlow<List<AartiItem>>(emptyList())
    val aartiItems: StateFlow<List<AartiItem>> = _aartiItemsFlow.asStateFlow()

    private val _playerStateFlow = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerStateFlow.asStateFlow()

    init {
        loadInitialAarties()
    }

    private fun loadInitialAarties() {
        _aartiItemsFlow.value = listOf(
            AartiItem(1, R.string.sukhakarta_dukhaharta_title, R.raw.sukhakarta_dukhaharta),
            AartiItem(2, R.string.durge_durghat_bhari_title, R.raw.durge_durghat_bhari),
            AartiItem(3, R.string.lavthavati_vikrala_title, R.raw.lavthavati_vikrala),
            AartiItem(4, R.string.yuge_atthavis_vitthala_title, R.raw.yuge_atthavis_vitthala),
            AartiItem(5, R.string.datta_aarti_title, R.raw.datta_aarti),
            AartiItem(6, R.string.ghalin_lotangan_vandin_charan_title, R.raw.ghalin_lotangan_vandin_charan)
        )
        updatePlayerPlaylistBasedOnSelection()
    }

    fun toggleAartiSelection(aartiItem: AartiItem, isChecked: Boolean) {
        _aartiItemsFlow.update { currentList ->
            currentList.map {
                if (it.id == aartiItem.id) it.copy(isChecked = isChecked) else it
            }
        }
        updatePlayerPlaylistBasedOnSelection()
    }

    fun moveAartiInPlaylist(fromPosition: Int, toPosition: Int) {
        _aartiItemsFlow.update { currentList ->
            val mutableList = currentList.toMutableList()
            if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                Collections.swap(mutableList, fromPosition, toPosition)
            }
            mutableList.toList()
        }
        updatePlayerPlaylistBasedOnSelection() // Ensure playlist order change is reflected
    }

    private fun updatePlayerPlaylistBasedOnSelection() {
        val selectedAarties = _aartiItemsFlow.value.filter { it.isChecked }
        _playerStateFlow.update { it.copy(currentPlaylist = selectedAarties) }
    }

    fun setPlayerState(
        isPlaying: Boolean,
        currentAartiId: Int?,
        positionMs: Long,
        durationMs: Long
    ) {
        val currentAarti = _aartiItemsFlow.value.find { it.id == currentAartiId }
        _playerStateFlow.update {
            it.copy(
                isPlaying = isPlaying,
                currentAarti = currentAarti,
                currentPositionMs = positionMs.coerceAtLeast(0L),
                totalDurationMs = durationMs.coerceAtLeast(0L)
            )
        }
    }

    fun onPlaylistFinished() {
        _aartiItemsFlow.update { currentList ->
            currentList.map { it.copy(isChecked = false) }
        }
        _playerStateFlow.update {
            it.copy(
                currentAarti = null,
                isPlaying = false,
                currentPositionMs = 0L,
                totalDurationMs = 0L,
                currentPlaylist = emptyList()
            )
        }
    }
}