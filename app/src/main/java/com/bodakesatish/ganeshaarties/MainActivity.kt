package com.bodakesatish.ganeshaarties

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem as Media3MediaItem // Alias to avoid confusion
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bodakesatish.ganeshaarties.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), AartiInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AartiViewModel by viewModels()
    private lateinit var playlistAdapter: AartiAdapter // Renamed
    private var itemTouchHelper: ItemTouchHelper? = null

    private lateinit var mediaControllerFuture: ListenableFuture<MediaController>
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture.isDone && !mediaControllerFuture.isCancelled) mediaControllerFuture.get() else null

    private val playerProgressHandler = Handler(Looper.getMainLooper())
    private var playerProgressPoller: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        // Control Status Bar Icon Color
//        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
//        // For light themes, tell the system that the status bar background is light,
//        // so it should use dark icons.
//        windowInsetsController.isAppearanceLightStatusBars = true // true for light status bar, false for dark

        initializeRecyclerView()
        initializePlayerControls()
        observeViewState()
    }

    private fun initializeRecyclerView() {
        playlistAdapter = AartiAdapter(this)
        binding.recyclerViewAarties.apply {
            adapter = playlistAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                return viewModel.playerState.value.isPlaying.not()
            }

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!viewModel.playerState.value.isPlaying) {
                    val fromPos = viewHolder.adapterPosition
                    val toPos = target.adapterPosition
                    if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                        viewModel.moveAartiInPlaylist(fromPos, toPos)
                    }
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper?.attachToRecyclerView(binding.recyclerViewAarties)
    }

    private fun initializePlayerControls() {
        binding.playerControlsContainer.buttonPlayPause.setOnClickListener {
            mediaController?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }
        binding.playerControlsContainer.buttonNext.setOnClickListener { mediaController?.seekToNextMediaItem() }
        binding.playerControlsContainer.buttonPrevious.setOnClickListener { mediaController?.seekToPreviousMediaItem() }

        binding.playerControlsContainer.seekBarPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaController?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun observeViewState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.aartiItems.collect { aartiList ->
                        playlistAdapter.submitList(aartiList)
                        // This collect block in viewModel.playerState handles playlist sync with controller
                    }
                }

                launch {
                    viewModel.playerState.collect { state ->
                        binding.playerControlsContainer.textViewCurrentSong.setText(
                            state.currentAarti?.title ?: R.string.no_aarti_selected
                        )
                        binding.playerControlsContainer.buttonPlayPause.setImageResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        )
                        binding.playerControlsContainer.seekBarPlayer.max = state.totalDurationMs.toInt().coerceAtLeast(0)
                        if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                            binding.playerControlsContainer.seekBarPlayer.progress = state.currentPositionMs.toInt().coerceAtLeast(0)
                        }
                        binding.playerControlsContainer.textViewCurrentTime.text = formatDuration(state.currentPositionMs)
                        binding.playerControlsContainer.textViewTotalTime.text = formatDuration(state.totalDurationMs)

                        playlistAdapter.updatePlaybackVisuals(state.currentAarti?.id, state.isPlaying)
                        playlistAdapter.setUserInteractionEnabled(!state.isPlaying)

                        synchronizePlaylistWithMediaController(state.currentPlaylist)


                        if (state.isPlaying) {
                            startPlayerProgressPoller()
                        } else {
                            stopPlayerProgressPoller()
                            // Ensure final UI update for position if paused or stopped
                            if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                                binding.playerControlsContainer.seekBarPlayer.progress = state.currentPositionMs.toInt().coerceAtLeast(0)
                            }
                            binding.playerControlsContainer.textViewCurrentTime.text = formatDuration(state.currentPositionMs)
                        }

                        // Enable/disable next/prev based on playlist existence
                        val hasPlaylist = state.currentPlaylist.isNotEmpty()
                        binding.playerControlsContainer.buttonNext.isEnabled = hasPlaylist && mediaController?.hasNextMediaItem() == true
                        binding.playerControlsContainer.buttonPrevious.isEnabled = hasPlaylist && mediaController?.hasPreviousMediaItem() == true
                        binding.playerControlsContainer.buttonPlayPause.isEnabled = hasPlaylist // Can play if playlist exists

                    }
                }
            }
        }
    }

    private fun startPlayerProgressPoller() {
        stopPlayerProgressPoller()
        if (mediaController == null) return

        playerProgressPoller = object : Runnable {
            override fun run() {
                mediaController?.let { controller ->
                    if (controller.isPlaying) {
                        viewModel.setPlayerState(
                            isPlaying = true,
                            currentAartiId = controller.currentMediaItem?.mediaId?.toIntOrNull(),
                            positionMs = controller.currentPosition,
                            durationMs = controller.duration
                        )
                        playerProgressHandler.postDelayed(this, 500)
                    } else {
                        // If not playing, ensure ViewModel has the final state before stopping poller
                        viewModel.setPlayerState(
                            isPlaying = false,
                            currentAartiId = controller.currentMediaItem?.mediaId?.toIntOrNull(),
                            positionMs = controller.currentPosition,
                            durationMs = controller.duration
                        )
                    }
                }
            }
        }
        playerProgressHandler.post(playerProgressPoller!!)
    }

    private fun stopPlayerProgressPoller() {
        playerProgressPoller?.let { playerProgressHandler.removeCallbacks(it) }
        playerProgressPoller = null
    }

    private fun synchronizePlaylistWithMediaController(playlist: List<AartiItem>) {
        mediaController?.let { controller ->
            val media3Items = playlist.map { aarti ->
                val uri = "android.resource://${packageName}/${aarti.rawResourceId}".toUri()
                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(getString(aarti.title)) // Get the string title
                    // You can add other metadata like artist, album art URI, etc.
                    // .setArtist("Your Artist")
                    // .setArtworkUri("path/to/artwork.jpg".toUri())
                    .build()
                Media3MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(aarti.id.toString())
                    // You can add more metadata here if needed by your notification/service
                     .setMediaMetadata(MediaMetadata.Builder()
                     .setTitle(getString(aarti.title)).build())
                    .build()
            }

            // Only update if the playlist content or order has actually changed.
            // This basic check compares IDs and order. More sophisticated diffing could be used.
            val currentControllerPlaylistIds = (0 until controller.mediaItemCount).mapNotNull {
                controller.getMediaItemAt(it).mediaId
            }
            val newPlaylistIds = media3Items.map { it.mediaId }

            if (currentControllerPlaylistIds != newPlaylistIds) {
                val wasPlaying = controller.isPlaying
                val currentMediaId = controller.currentMediaItem?.mediaId
                val currentWindowIndex = controller.currentMediaItemIndex
                val currentPosition = controller.currentPosition

                controller.setMediaItems(media3Items, false) // resetPosition = false initially

                if (media3Items.isEmpty()) {
                    if (wasPlaying || controller.playbackState != Player.STATE_IDLE) {
                        if (controller.isCommandAvailable(Player.COMMAND_STOP)) {
                            controller.stop()
                            controller.clearMediaItems() // Also clear them explicitly
                        }
                    }
                } else {
                    // Try to restore playback position if the playing item is still in the new playlist
                    val newIndexOfCurrentItem = media3Items.indexOfFirst { it.mediaId == currentMediaId }
                    if (newIndexOfCurrentItem != -1) {
                        controller.seekTo(newIndexOfCurrentItem, currentPosition)
                    } else if (currentWindowIndex < media3Items.size) {
                        // Fallback: seek to the same index if item changed but index is valid
                        controller.seekToDefaultPosition(currentWindowIndex)
                    } else {
                        // Fallback: seek to the beginning of the new playlist
                        controller.seekToDefaultPosition(0)
                    }

                    if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                        if (controller.isCommandAvailable(Player.COMMAND_PREPARE)) {
                            controller.prepare()
                        }
                    }
                    if (wasPlaying && !controller.isPlaying) {
                        controller.play()
                    }
                }
            }
        }
    }


    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            mediaController?.addListener(mediaPlayerListener)
            mediaController?.let {
                viewModel.setPlayerState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = it.currentPosition,
                    durationMs = it.duration
                )
                if (it.isPlaying) startPlayerProgressPoller() else stopPlayerProgressPoller()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        mediaController?.removeListener(mediaPlayerListener)
        MediaController.releaseFuture(mediaControllerFuture)
        stopPlayerProgressPoller()
    }

    override fun onAartiToggled(aarti: AartiItem, isChecked: Boolean) {
        viewModel.toggleAartiSelection(aarti, isChecked)
    }

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper?.startDrag(viewHolder)
    }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private val mediaPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            viewModel.setPlayerState(
                isPlaying = isPlaying,
                currentAartiId = mediaController?.currentMediaItem?.mediaId?.toIntOrNull(),
                positionMs = mediaController?.currentPosition ?: 0L,
                durationMs = mediaController?.duration ?: 0L
            )
            // Poller start/stop is handled by the viewModel.playerState collector
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            viewModel.setPlayerState(
                isPlaying = mediaController?.isPlaying ?: false,
                currentAartiId = mediaItem?.mediaId?.toIntOrNull(),
                positionMs = 0L, // Position resets on transition
                durationMs = mediaController?.duration ?: 0L
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                viewModel.onPlaylistFinished()
                // UI resets for buttons/text are handled by viewModel.playerState collector
            } else {
                // For other states, ensure the ViewModel is updated with current timing info
                mediaController?.let {
                    viewModel.setPlayerState(
                        isPlaying = it.isPlaying,
                        currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                        positionMs = it.currentPosition,
                        durationMs = it.duration
                    )
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            mediaController?.let {
                viewModel.setPlayerState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = newPosition.positionMs,
                    durationMs = it.duration
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_language -> {
                displayLanguageSelectionDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun displayLanguageSelectionDialog() {
        val languages = arrayOf("English", "मराठी (Marathi)")
        val languageCodes = arrayOf("en", "mr")

        val currentLangCode = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag().take(2)
        val checkedItem = languageCodes.indexOf(currentLangCode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language_title))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCodes[which]))
                dialog.dismiss()
                // Activity will be recreated.
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}