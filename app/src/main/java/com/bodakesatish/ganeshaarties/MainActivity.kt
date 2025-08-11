package com.bodakesatish.ganeshaarties

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.bodakesatish.ganeshaarties.databinding.ActivityMainBinding // Generated binding class for activity_main
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.collections.filter

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            // viewModel.onNextClicked()
        }

        binding.playerControlsContainer.buttonPrevious.setOnClickListener {
            player?.seekToPreviousMediaItem()
            // viewModel.onPreviousClicked()
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

                        binding.playerControlsContainer.textViewCurrentSong.text =
                            state.currentAarti?.title ?: "No Aarti Selected"
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


                        val isPlaying = state.isPlaying
                        // ... (button enable/disable states)
                        aartiAdapter.setInteractionsEnabled(!isPlaying)

                        if (isPlaying) {
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
                        // Schedule the next update
                        progressUpdateHandler.postDelayed(this, 500) // Update every 500ms
                    } else {
                        // If player stopped playing for some other reason, update state one last time
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
                val uri = Uri.parse("android.resource://${packageName}/${aarti.rawResourceId}")
                androidx.media3.common.MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(aarti.id.toString())
                    .build()
            }

            // Get the current state *before* setting new items
            val currentPlayingMediaId = controller.currentMediaItem?.mediaId
            val currentPosition = controller.currentPosition
            val wasPlaying = controller.isPlaying // Important: check if it was playing

            // Set media items. The `preserveConfiguration=true` is key.
            // It tries to maintain the playback state (play/pause, current item if still present, position).
            controller.setMediaItems(mediaItems, true) // `resetPosition` defaults to false, `playWhenReady` is preserved.

            // After setting items, the controller might have intelligently handled things.
            // For example, if the current item was removed and it was playing, it might stop or move to the next.

            // If the playlist became empty, and it was playing, it should stop.
            // The MediaController might do this automatically, but an explicit stop is safer.
            if (mediaItems.isEmpty() && wasPlaying) {
                if (controller.isCommandAvailable(Player.COMMAND_STOP)) {
                    controller.stop()
                }
            }
            // If the playlist is not empty, and the controller is in an IDLE state (e.g., after being empty)
            // it needs to be prepared.
            else if (mediaItems.isNotEmpty() && controller.playbackState == Player.STATE_IDLE) {
                if (controller.isCommandAvailable(Player.COMMAND_PREPARE)) {
                    controller.prepare()
                }
            }
            // If it *was* playing, and the new playlist is not empty,
            // but for some reason the controller stopped (e.g. current item removed and no auto-next),
            // we should NOT automatically restart it here. The user should press play again
            // if they want to start a new track from the updated playlist.
            // The `preserveConfiguration=true` in setMediaItems should handle most cases
            // of continuing playback if the current item is still valid.
        }
    }


    override fun onStart() {
        super.onStart()
        // Initialize MediaController
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            val connectedController = player // Safe access to the controller
            connectedController?.addListener(playerListener) // Add listener to get continuous updates

            // Crucially, once the controller is connected, refresh the ViewModel's state
            // based on the *current* state of the player from the service.
            connectedController?.let {
                // Update ViewModel with the latest state from the MediaController
                viewModel.updatePlaybackState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    position = it.currentPosition,
                    duration = it.duration
                )
                // If it's playing, ensure the seekbar poller starts
                if (it.isPlaying) {
                    startSeekBarUpdates()
                } else {
                    stopSeekBarUpdates() // Ensure it's stopped if player isn't playing
                    // Also, ensure UI reflects the potentially non-zero paused position
                    if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
                        binding.playerControlsContainer.seekBarPlayer.progress = it.currentPosition.toInt().coerceAtLeast(0)
                    }
                    binding.playerControlsContainer.textViewCurrentTime.text = formatTime(it.currentPosition)
                }

                // You might also want to re-fetch the playlist if it could have changed
                // while the UI was not visible. However, your current setup where
                // updateServicePlaylist is called on aartiesStateFlow changes might be sufficient.
                // Consider if an explicit playlist sync is needed here.
                // For example:
                // val initialCheckedAarties = viewModel.aartiesStateFlow.value.filter { a -> a.isChecked }
                // updateServicePlaylist(initialCheckedAarties)
                // if (initialCheckedAarties.isNotEmpty() && it.playbackState == Player.STATE_IDLE) {
                //    it.prepare()
                // }
            }
        }, ContextCompat.getMainExecutor(this))
    }


    // onNewIntent() is relevant if you need to handle specific data passed IN the intent
// from the notification, but for just bringing the activity to front and refreshing,
// onStart() and the MediaController connection are usually enough.
// override fun onNewIntent(intent: Intent?) {
//    super.onNewIntent(intent)
//    // If the intent from the notification carried specific data you need to process, do it here.
//    // For example, if the notification intent had an extra like "play_specific_track_id".
//    // Then, re-trigger UI updates or player actions.
// }

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
                binding.playerControlsContainer.textViewCurrentSong.text = "No Aarti Selected"
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

    private fun buildNotification(): Notification { // Or however you create your notification
        val sessionActivityPendingIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
            // Ensure this intent will bring MainActivity to front or create it
            sessionIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP // Good flags
            PendingIntent.getActivity(this, 0, sessionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        // When building your notification:
        val notificationBuilder = NotificationCompat.Builder(this, "YOUR_NOTIFICATION_CHANNEL_ID")
            // ... other notification settings (icon, title, actions)
            .setContentIntent(sessionActivityPendingIntent) // THIS IS KEY
        // ...

        return notificationBuilder.build()
    }

}
