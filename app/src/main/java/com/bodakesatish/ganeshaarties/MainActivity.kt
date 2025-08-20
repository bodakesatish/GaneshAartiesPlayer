package com.bodakesatish.ganeshaarties

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bodakesatish.ganeshaarties.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class) // For Media3 APIs and Log
class MainActivity : AppCompatActivity(), AartiInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AartiViewModel by viewModels()
    private lateinit var playlistAdapter: AartiAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    private lateinit var mediaControllerFuture: ListenableFuture<MediaController>
    private val mediaController: MediaController?
        get() = if (mediaControllerFuture.isDone && !mediaControllerFuture.isCancelled) mediaControllerFuture.get() else null

    private val playerProgressHandler = Handler(Looper.getMainLooper())
    private var playerProgressPoller: Runnable? = null

    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "MainActivity" // For logging
        const val PREFS_NAME = "theme_prefs"
        const val KEY_THEME = "selected_theme"
        const val THEME_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
        const val THEME_DARK = AppCompatDelegate.MODE_NIGHT_YES
        const val THEME_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        private const val POLLING_INTERVAL_MS = 500L
    }

    // Define a launcher for the permission request
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                // Permission is granted. Continue the action or initialize features that need it.
                // For example, if you deferred starting playback, you can start it now.
                // Or simply allow playback knowing notifications will work.
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS permission denied.")
                // Explain to the user that the feature is unavailable because the
                // features requires a permission that the user has denied.
                // At a minimum, inform the user that notifications won't be shown.
                Snackbar.make(
                    binding.root,
                    getString(R.string.notification_permission_denied_message), // Add this string resource
                    Snackbar.LENGTH_LONG
                ).setAction(getString(R.string.settings)) { // Add this string resource
                    // Optionally, direct the user to app settings
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }.show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedTheme = sharedPreferences.getInt(KEY_THEME, THEME_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(selectedTheme)

        WindowCompat.setDecorFitsSystemWindows(window, false) // Enable Edge-to-Edge
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        updateStatusBarIconColor()
        initializeRecyclerView()
        initializePlayerControls()
        observeViewModel() // Renamed for clarity


        // It's a good practice to check/request notification permission early
        // if notifications are a core part of the experience.
        // Or, you can check it right before an action that triggers a notification.
        // For simplicity, let's check it here. If you prefer, move this check
        // to right before the first play action or service start.
        checkAndRequestNotificationPermission()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU is Android 13
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "POST_NOTIFICATIONS permission already granted.")
                    // You can use the API that requires the permission.
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Explain to the user why your app needs the notification permission.
                    // This is shown if the user has previously denied the request.
                    Log.d(TAG, "Showing rationale for POST_NOTIFICATIONS permission.")
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.notification_permission_title)) // Add string resource
                        .setMessage(getString(R.string.notification_permission_rationale)) // Add string resource
                        .setPositiveButton(getString(R.string.ok)) { _, _ -> // Add string resource
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton(getString(R.string.cancel), null) // Add string resource
                        .show()
                }
                else -> {
                    // Directly ask for the permission.
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No runtime permission needed for notifications on Android 12L and below
            Log.d(TAG, "Notification permission not required for this API level.")
        }
    }

    private fun updateStatusBarIconColor() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isLightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        windowInsetsController.isAppearanceLightStatusBars = isLightMode
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
            override fun isLongPressDragEnabled() = !viewModel.playerState.value.isPlaying
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!viewModel.playerState.value.isPlaying) {
                    val fromPos = viewHolder.bindingAdapterPosition // Use bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition   // Use bindingAdapterPosition
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
            mediaController?.let { controller ->
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    if (controller.mediaItemCount > 0) {
                        controller.playWhenReady = true // Ensure intent to play
                        controller.play()
                    } else {
                        // Optionally, if trying to play with an empty list, and you require
                        // users to select aarties first, prompt them.
                        // And if notification permission is not granted, this might be a good time
                        // to remind them if notifications are integral to the playback experience.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            Snackbar.make(binding.root, getString(R.string.select_aarti_and_noti_perm_reminder), Snackbar.LENGTH_LONG).show() // Add string
                        } else {
                            Snackbar.make(binding.root, getString(R.string.please_select_aarti_to_play), Snackbar.LENGTH_SHORT).show() // Add string
                        }
                    }
                }
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.aartiItems.collect { aartiList ->
                        playlistAdapter.submitList(aartiList)
                    }
                }

                launch {
                    viewModel.playerState.collect { state ->
                        updatePlayerUi(state)
                        synchronizePlaylistWithMediaController(state.currentPlaylist)
                        // Poller is managed by MediaController events now, not directly here.
                    }
                }
            }
        }
    }

    private fun updatePlayerUi(state: PlayerUiState) {
        binding.playerControlsContainer.textViewCurrentSong.setText(
            state.currentAarti?.title ?: R.string.no_aarti_selected
        )
        binding.playerControlsContainer.buttonPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )

        binding.playerControlsContainer.seekBarPlayer.max = state.totalDurationMs.toInt().coerceAtLeast(0)
        if (!binding.playerControlsContainer.seekBarPlayer.isPressed) { // Only update if user is not dragging
            binding.playerControlsContainer.seekBarPlayer.progress = state.currentPositionMs.toInt().coerceAtLeast(0)
        }

        binding.playerControlsContainer.textViewCurrentTime.text = formatDuration(state.currentPositionMs)
        binding.playerControlsContainer.textViewTotalTime.text = formatDuration(state.totalDurationMs)

        playlistAdapter.updatePlaybackVisuals(state.currentAarti?.id, state.isPlaying)
        playlistAdapter.setUserInteractionEnabled(!state.isPlaying)

        val hasPlaylist = state.currentPlaylist.isNotEmpty()
        val controller = mediaController // Cache for stable access
        binding.playerControlsContainer.buttonNext.isEnabled = hasPlaylist && controller?.hasNextMediaItem() == true
        binding.playerControlsContainer.buttonPrevious.isEnabled = hasPlaylist && controller?.hasPreviousMediaItem() == true
        binding.playerControlsContainer.buttonPlayPause.isEnabled = hasPlaylist || controller?.isPlaying == true // Can pause if playing, or play if playlist exists
    }


    private fun startOrUpdatePlayerProgressPoller() {
        stopPlayerProgressPoller() // Stop any existing poller first

        val currentMediaController = this.mediaController
        if (currentMediaController == null || !currentMediaController.isConnected || !currentMediaController.isPlaying) {
            Log.d(TAG, "Poller not started: Controller null, not connected, or not playing.")
            return
        }

        Log.d(TAG, "Poller INITIATED. isPlaying: ${currentMediaController.isPlaying}")

        playerProgressPoller = object : Runnable {
            override fun run() {
                val controllerInPoll = this@MainActivity.mediaController // Re-fetch for safety
                if (controllerInPoll != null && controllerInPoll.isConnected && controllerInPoll.isPlaying) {
                    viewModel.setPlayerState(
                        isPlaying = true,
                        currentAartiId = controllerInPoll.currentMediaItem?.mediaId?.toIntOrNull(),
                        positionMs = controllerInPoll.currentPosition,
                        durationMs = controllerInPoll.duration.coerceAtLeast(0L) // Ensure duration is non-negative
                    )
                    playerProgressHandler.postDelayed(this, POLLING_INTERVAL_MS)
                } else {
                    Log.d(TAG, "Poller stopping: Controller null, not connected, or not playing.")
                    // Do not call stopPlayerProgressPoller() here to avoid potential recursion
                    // if this runnable is somehow posted again before being removed.
                    // The outer logic in onIsPlayingChanged or onStop will handle stopping.
                    // If it stopped playing, onIsPlayingChanged(false) should have been called,
                    // which would call stopPlayerProgressPoller().
                }
            }
        }
        playerProgressHandler.post(playerProgressPoller!!)
    }

    private fun stopPlayerProgressPoller() {
        playerProgressPoller?.let {
            playerProgressHandler.removeCallbacks(it)
            Log.d(TAG, "Poller STOPPED.")
        }
        playerProgressPoller = null
    }

    private fun synchronizePlaylistWithMediaController(newPlaylist: List<AartiItem>) {
        mediaController?.let { controller ->
            val media3Items = newPlaylist.map { aarti ->
                val uri = "android.resource://${packageName}/${aarti.rawResourceId}".toUri()
                Media3MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(aarti.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(aarti.title)).build())
                    .build()
            }

            val currentControllerPlaylistIds = (0 until controller.mediaItemCount).mapNotNull {
                controller.getMediaItemAt(it).mediaId
            }
            val newPlaylistIds = media3Items.map { it.mediaId }

            if (currentControllerPlaylistIds != newPlaylistIds) {
                Log.d(TAG, "Syncing playlist. New items count: ${media3Items.size}")

                val wasPlayingBeforeUpdate = controller.isPlaying
                val playWhenReadyBeforeUpdate = controller.playWhenReady // Use this one
                val previousPlaybackState = controller.playbackState

                // Determine if the player was effectively empty/stopped before this sync
                val playerWasEffectivelyEmpty = currentControllerPlaylistIds.isEmpty() &&
                        (previousPlaybackState == Player.STATE_IDLE || previousPlaybackState == Player.STATE_ENDED)

                if (media3Items.isEmpty()) {
                    Log.d(TAG, "New playlist is empty. Stopping and clearing player.")
                    controller.stop() // Also sets playWhenReady = false
                    controller.clearMediaItems()
                    viewModel.onPlaylistFinished()
                } else {
                    // Decide whether to reset position (start from beginning of new playlist)
                    // Reset if the player was previously empty/stopped, or if the current item is no longer in the new list (Media3 handles this partly)
                    val resetPosition = playerWasEffectivelyEmpty

                    Log.d(TAG, "Setting new media items. Reset position: $resetPosition")
                    controller.setMediaItems(media3Items, resetPosition)

                    // Restore playWhenReady state unless we explicitly want it to be false
                    // (e.g. after playlist ended or if was previously not set to play when ready)
                    if (previousPlaybackState == Player.STATE_ENDED && !wasPlayingBeforeUpdate) {
                        controller.playWhenReady = false // Stay paused if playlist ended and wasn't playing
                    } else {
                        controller.playWhenReady = playWhenReadyBeforeUpdate
                    }

                    if (controller.playbackState == Player.STATE_IDLE || (controller.playbackState == Player.STATE_ENDED && media3Items.isNotEmpty())) {
                        controller.prepare()
                        Log.d(TAG, "Player prepared.")
                    }

                    // If it was playing and playWhenReady is true, but it's not playing now (e.g. due to setMediaItems/prepare), explicitly play.
                    if (wasPlayingBeforeUpdate && controller.playWhenReady && !controller.isPlaying) {
                        Log.d(TAG, "Resuming play after playlist sync.")
                        controller.play()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            mediaController?.addListener(mediaPlayerListener)
            // Initial state sync from controller to ViewModel
            mediaController?.let { mc ->
                viewModel.setPlayerState(
                    isPlaying = mc.isPlaying,
                    currentAartiId = mc.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = mc.currentPosition,
                    durationMs = mc.duration.coerceAtLeast(0L)
                )
                if (mc.isPlaying) { // Start poller if playing
                    startOrUpdatePlayerProgressPoller()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        mediaController?.removeListener(mediaPlayerListener)
        MediaController.releaseFuture(mediaControllerFuture) // Important to release the future
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
            Log.d(TAG, "onIsPlayingChanged: $isPlaying, MediaId: ${mediaController?.currentMediaItem?.mediaId}")
            val currentController = mediaController ?: return
            viewModel.setPlayerState(
                isPlaying = isPlaying,
                currentAartiId = currentController.currentMediaItem?.mediaId?.toIntOrNull(),
                positionMs = currentController.currentPosition,
                durationMs = currentController.duration.coerceAtLeast(0L)
            )
            if (isPlaying) {
                startOrUpdatePlayerProgressPoller()
            } else {
                stopPlayerProgressPoller()
                // Update final position when pausing/stopping, poller might not catch it
                viewModel.setPlayerState( // Call again to ensure final state capture
                    isPlaying = false,
                    currentAartiId = currentController.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = currentController.currentPosition,
                    durationMs = currentController.duration.coerceAtLeast(0L)
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            Log.d(TAG, "onMediaItemTransition to ${mediaItem?.mediaId}, reason: $reason")
            val currentController = mediaController ?: return
            viewModel.setPlayerState(
                isPlaying = currentController.isPlaying, // isPlaying might still be true
                currentAartiId = mediaItem?.mediaId?.toIntOrNull(),
                positionMs = 0L, // Position usually resets on transition
                durationMs = currentController.duration.coerceAtLeast(0L)
            )
            // If it's still playing after transition, ensure poller continues/restarts
            if (currentController.isPlaying) {
                startOrUpdatePlayerProgressPoller()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString = when(playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN ($playbackState)"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateString, isPlaying: ${mediaController?.isPlaying}")

            val currentController = mediaController
            if (playbackState == Player.STATE_ENDED) {
                viewModel.onPlaylistFinished() // This will also update PlayerUiState(isPlaying=false)
                stopPlayerProgressPoller()
            } else if (currentController != null) {
                // For other states like READY or BUFFERING, update state if needed,
                // especially if isPlaying changed or duration became available.
                viewModel.setPlayerState(
                    isPlaying = currentController.isPlaying,
                    currentAartiId = currentController.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = currentController.currentPosition,
                    durationMs = currentController.duration.coerceAtLeast(0L)
                )
                // Ensure poller state is correct based on current isPlaying status
                if (currentController.isPlaying) {
                    startOrUpdatePlayerProgressPoller()
                } else {
                    stopPlayerProgressPoller()
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            Log.d(TAG, "onPositionDiscontinuity from ${oldPosition.positionMs} to ${newPosition.positionMs}, reason: $reason")
            mediaController?.let {
                viewModel.setPlayerState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = newPosition.positionMs, // Use the new position
                    durationMs = it.duration.coerceAtLeast(0L)
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val selectedTheme = sharedPreferences.getInt(KEY_THEME, THEME_SYSTEM)
        when (selectedTheme) {
            THEME_LIGHT -> menu.findItem(R.id.action_theme_light)?.isChecked = true
            THEME_DARK -> menu.findItem(R.id.action_theme_dark)?.isChecked = true
            THEME_SYSTEM -> menu.findItem(R.id.action_theme_system)?.isChecked = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val currentNightMode = AppCompatDelegate.getDefaultNightMode()
        val newNightMode = when (item.itemId) {
            R.id.action_theme_light -> THEME_LIGHT
            R.id.action_theme_dark -> THEME_DARK
            R.id.action_theme_system -> THEME_SYSTEM
            R.id.action_change_language -> {
                displayLanguageSelectionDialog()
                return true // Handled
            }
            else -> return super.onOptionsItemSelected(item)
        }

        if (currentNightMode != newNightMode) {
            applyThemeChange(newNightMode)
        }
        item.isChecked = true // Check the selected item
        return true
    }

    private fun applyThemeChange(themeMode: Int) {
        AppCompatDelegate.setDefaultNightMode(themeMode)
        sharedPreferences.edit().putInt(KEY_THEME, themeMode).apply()
        recreate() // Recreate is necessary for a full theme application
    }

    private fun displayLanguageSelectionDialog() {
        val languages = arrayOf(getString(R.string.language_english), getString(R.string.language_marathi)) // Use string resources
        val languageCodes = arrayOf("en", "mr")

        val currentLangCode = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag().take(2)
        val checkedItem = languageCodes.indexOf(currentLangCode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language_title))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageCodes[which]))
                dialog.dismiss()
                // Activity will be recreated automatically by setApplicationLocales
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}