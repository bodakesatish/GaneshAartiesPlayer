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
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

class MainActivity : AppCompatActivity(), AartiItemListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AartiViewModel by viewModels() // Use the same ViewModel
    private lateinit var aartiAdapter: AartiAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    // Media Controller related
    private lateinit var mediaControllerFuture: ListenableFuture<MediaController>
    private val player: MediaController?
        get() = if (mediaControllerFuture.isDone && !mediaControllerFuture.isCancelled) mediaControllerFuture.get() else null

    // MusicService related (same as before, but binding might be different if not using MediaController solely)
    // private var musicService: MusicService? = null
    // private var isServiceBound = false
    // private val serviceConnection = object : ServiceConnection { ... }


    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private var progressUpdateRunnable: Runnable? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // Enable edge-to-edge

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up the Toolbar
        val toolbar: Toolbar = binding.toolbar // If using ViewBinding
        // Or: val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name) // Set your desired title


        setupRecyclerView()
        setupPlayerControls()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        aartiAdapter = AartiAdapter(this)
        binding.recyclerViewAarties.apply {
            adapter = aartiAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0 // No swipe actions
        ) {
            override fun isLongPressDragEnabled(): Boolean {
                // Only allow dragging if music is not playing
                // You'll get isPlaying state from the ViewModel's playerUiState
                return viewModel.playerUiState.value.isPlaying.not()
            }

            override fun isItemViewSwipeEnabled(): Boolean {
                return false // Swiping not used
            }
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Check again here as a safeguard, though isLongPressDragEnabled should mostly cover it
                if (!viewModel.playerUiState.value.isPlaying) {
                    val fromPosition = viewHolder.adapterPosition
                    val toPosition = target.adapterPosition
                    if (fromPosition != RecyclerView.NO_POSITION && toPosition != RecyclerView.NO_POSITION) {
                        viewModel.moveAarti(fromPosition, toPosition)
                    }
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }
            // Optional: Customize appearance during drag
            // override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) { ... }
            // override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) { ... }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(binding.recyclerViewAarties)
    }

    private fun setupPlayerControls() {
        binding.playerControlsContainer.buttonPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
            // Alternative: viewModel.onPlayPauseClicked() if ViewModel sends command to service
        }

        binding.playerControlsContainer.buttonNext.setOnClickListener {
            player?.seekToNextMediaItem()
        }

        binding.playerControlsContainer.buttonPrevious.setOnClickListener {
            player?.seekToPreviousMediaItem()
        }

        binding.playerControlsContainer.seekBarPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    // viewModel.onSeekBarPositionChanged(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe the list of aarties
                launch {
                    viewModel.aartiesStateFlow.collect { aartiList ->
                        aartiAdapter.submitList(aartiList)

                        // Then, explicitly update the playing state based on the current playerUiState
                        // This ensures that if the list updates, the playing highlight is reapplied correctly.
                        val currentUiState = viewModel.playerUiState.value
                        aartiAdapter.setPlayingState(currentUiState.currentAarti?.id, currentUiState.isPlaying)


                        // Only update the service's playlist. Don't auto-play here.
                        // The decision to play will be handled by play/pause button or
                        // if playback was already active.
                        val checkedAarties = aartiList.filter { it.isChecked }
                        updateServicePlaylist(checkedAarties) // Renamed for clarity
                    }
                }

                // Observe player UI state
                launch {
                    viewModel.playerUiState.collect { state ->

                        binding.playerControlsContainer.textViewCurrentSong.setText(
                            state.currentAarti?.title ?: R.string.no_aarti_selected)
                        binding.playerControlsContainer.buttonPlayPause.setImageResource(
                            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        )
                        // SeekBar updates
                        binding.playerControlsContainer.seekBarPlayer.max = state.totalDuration.toInt().coerceAtLeast(0) // Will be 0
                        if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                            binding.playerControlsContainer.seekBarPlayer.progress = state.currentPosition.toInt().coerceAtLeast(0) // Will be 0
                        }

                        // Time TextViews
                        binding.playerControlsContainer.textViewCurrentTime.text = formatTime(state.currentPosition) // Will be 00:00
                        binding.playerControlsContainer.textViewTotalTime.text = formatTime(state.totalDuration)     // Will be 00:00

                        // Update adapter's playing state
                        aartiAdapter.setPlayingState(state.currentAarti?.id, state.isPlaying) // <--- ADD THIS

                        // Enable/disable interactions based on playing state
                        aartiAdapter.setInteractionsEnabled(!state.isPlaying)


                        if (state.isPlaying) {
                            startSeekBarUpdates()
                        } else {
                            stopSeekBarUpdates()
                            // Ensure final position is set when stopping
                            if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                                binding.playerControlsContainer.seekBarPlayer.progress = state.currentPosition.toInt().coerceAtLeast(0)
                            }
                            binding.playerControlsContainer.textViewCurrentTime.text = formatTime(state.currentPosition)
                        }
                    }
                }
            }
        }
    }

    private fun startSeekBarUpdates() {
        stopSeekBarUpdates() // Stop any existing runnable
        if (player == null) return

        progressUpdateRunnable = object : Runnable {
            override fun run() {
                player?.let { controller ->
                    if (controller.isPlaying) {
                        val currentPosition = controller.currentPosition
                        val duration = controller.duration
                        viewModel.updatePlaybackState( // Update ViewModel which triggers UI
                            isPlaying = true,
                            currentAartiId = controller.currentMediaItem?.mediaId?.toIntOrNull(),
                            position = currentPosition,
                            duration = duration
                        )
                        progressUpdateHandler.postDelayed(this, 500) // Update every 500ms
                    } else {
                        viewModel.updatePlaybackState(
                            isPlaying = false,
                            currentAartiId = controller.currentMediaItem?.mediaId?.toIntOrNull(),
                            position = controller.currentPosition, // get final position
                            duration = controller.duration
                        )
                    }
                }
            }
        }
        progressUpdateHandler.post(progressUpdateRunnable!!)
    }

    private fun stopSeekBarUpdates() {
        progressUpdateRunnable?.let { progressUpdateHandler.removeCallbacks(it) }
        progressUpdateRunnable = null
    }

    private fun updateServicePlaylist(playlist: List<AartiItem>) {
        player?.let { controller ->
            val mediaItems = playlist.map { aarti ->
                val uri = "android.resource://${packageName}/${aarti.rawResourceId}".toUri()
                androidx.media3.common.MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(aarti.id.toString())
                    .build()
            }

            val wasPlaying = controller.isPlaying
            controller.setMediaItems(mediaItems, true)
            if (mediaItems.isEmpty() && wasPlaying) {
                if (controller.isCommandAvailable(Player.COMMAND_STOP)) {
                    controller.stop()
                }
            }
            else if (mediaItems.isNotEmpty() && controller.playbackState == Player.STATE_IDLE) {
                if (controller.isCommandAvailable(Player.COMMAND_PREPARE)) {
                    controller.prepare()
                }
            }
        }
    }


    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        // Initialize MediaController
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            val connectedController = player // Safe access to the controller
            connectedController?.addListener(playerListener) // Add listener to get continuous updates
            connectedController?.let {
                viewModel.updatePlaybackState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    position = it.currentPosition,
                    duration = it.duration
                )
                if (it.isPlaying) {
                    startSeekBarUpdates()
                } else {
                    stopSeekBarUpdates()
                    if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                        binding.playerControlsContainer.seekBarPlayer.progress = it.currentPosition.toInt().coerceAtLeast(0)
                    }
                    binding.playerControlsContainer.textViewCurrentTime.text = formatTime(it.currentPosition)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        player?.removeListener(playerListener)
        MediaController.releaseFuture(mediaControllerFuture)
        stopSeekBarUpdates() // Stop updates when activity is not visible
    }

    // AartiItemListener implementation
    override fun onAartiToggled(aarti: AartiItem, isChecked: Boolean) {
        viewModel.onAartiCheckedChanged(aarti, isChecked)
        // The observer for viewModel.aartiesStateFlow will handle updating the player
    }

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper?.startDrag(viewHolder)
    }

    private fun formatTime(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // Player.Listener (same as in Compose version to update ViewModel)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val currentMediaId = player?.currentMediaItem?.mediaId
            val position = player?.currentPosition ?: 0L
            val duration = player?.duration ?: 0L // Get duration here

            viewModel.updatePlaybackState(
                isPlaying = isPlaying,
                currentAartiId = currentMediaId?.toIntOrNull(),
                position = position,
                duration = duration // Pass duration
            )
            // Start/stop poller (this logic is already in your observeViewModel,
            // but direct calls can be slightly more responsive here)
            if (isPlaying) {
                startSeekBarUpdates()
            } else {
                stopSeekBarUpdates()
                // Ensure final UI update for position if paused or stopped not due to playlist end
                if (player?.playbackState != Player.STATE_ENDED) {
                    if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                        binding.playerControlsContainer.seekBarPlayer.progress = position.toInt().coerceAtLeast(0)
                    }
                    binding.playerControlsContainer.textViewCurrentTime.text = formatTime(position)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val newPosition = 0L
            val newDuration = player?.duration ?: 0L
            viewModel.updatePlaybackState(
                isPlaying = player?.isPlaying ?: false,
                currentAartiId = mediaItem?.mediaId?.toIntOrNull(),
                position = newPosition,
                duration = newDuration
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentPosition = player?.currentPosition ?: 0L
            val duration = player?.duration ?: 0L
            val isPlaying = player?.isPlaying ?: false // isPlaying might still be true briefly before STATE_ENDED fully processes

            if (playbackState == Player.STATE_ENDED) {
                // Playlist has finished
                stopSeekBarUpdates() // Stop polling for progress

                // Update ViewModel to reflect playlist ended state
                viewModel.handlePlaylistEnded() // We'll create this method in ViewModel

                // It's also good to directly reset UI elements that the ViewModel might not
                // immediately clear if its state update is generic.
                binding.playerControlsContainer.textViewCurrentSong.text = getString(R.string.no_aarti_selected)
                binding.playerControlsContainer.seekBarPlayer.progress = 0
                binding.playerControlsContainer.textViewCurrentTime.text = formatTime(0)
                // Keep total duration as 0 or from last item, or reset explicitly
                binding.playerControlsContainer.seekBarPlayer.max = 0 // Reset max as well
                binding.playerControlsContainer.textViewTotalTime.text = formatTime(0)
                binding.playerControlsContainer.buttonPlayPause.setImageResource(R.drawable.ic_play_arrow)
                // Disable next/prev buttons as there's nothing to play
                binding.playerControlsContainer.buttonNext.isEnabled = false
                binding.playerControlsContainer.buttonPrevious.isEnabled = false


            } else {
                // For other states like READY, BUFFERING, update the generic playback state
                viewModel.updatePlaybackState(
                    isPlaying = isPlaying, // Use the current isPlaying state
                    currentAartiId = player?.currentMediaItem?.mediaId?.toIntOrNull(),
                    position = currentPosition,
                    duration = duration
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            viewModel.updatePlaybackState(
                isPlaying = player?.isPlaying ?: false,
                currentAartiId = player?.currentMediaItem?.mediaId?.toIntOrNull(),
                position = newPosition.positionMs,
                duration = player?.duration ?: 0L
            )
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu) // Create main_menu.xml
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_language -> {
                showLanguageSelectionDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLanguageSelectionDialog() {
        val languages = arrayOf("English", "मराठी (Marathi)")
        val languageCodes = arrayOf("en", "mr")

        val currentAppLocales = AppCompatDelegate.getApplicationLocales()
        val currentLangCode = if (!currentAppLocales.isEmpty) {
            currentAppLocales.get(0)?.toLanguageTag() ?: "en" // Default to English if null
        } else {
            // Fallback if empty (should ideally pick system default or your app's default)
            Locale.getDefault().toLanguageTag().take(2)
        }

        val checkedItem = languageCodes.indexOf(currentLangCode).coerceAtLeast(0)


        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language_title)) // Add to strings.xml
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val selectedLanguageCode = languageCodes[which]
                setAppLocale(selectedLanguageCode)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> // Add to strings.xml
                dialog.dismiss()
            }
            .show()
    }

    private fun setAppLocale(languageCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(languageCode)
        // Call this on the main thread as it may re-create the Activity
        runOnUiThread {
            AppCompatDelegate.setApplicationLocales(appLocale)
            // Note: The Activity will often be recreated by the system after calling
            // setApplicationLocales. If not, you might need to manually recreate it
            // for changes to fully apply, especially for complex UIs or older Android versions.
            // For simple string changes, it might reflect immediately or after the next configuration change.
            // For a more robust immediate update, you might call recreate() here,
            // but be mindful of state saving.
        }
    }

}
