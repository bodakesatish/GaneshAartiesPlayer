package com.bodakesatish.ganeshaarties

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bodakesatish.ganeshaarties.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
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

    private lateinit var userPreferencesRepository: UserPreferencesRepository

    companion object {
        private const val TAG = "MainActivity"
        const val THEME_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
        const val THEME_DARK = AppCompatDelegate.MODE_NIGHT_YES
        const val THEME_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        private const val POLLING_INTERVAL_MS = 500L

        private const val FIRESTORE_COLLECTION_INSTALLATIONS = "GaneshAartiesInstallations"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.notification_permission_denied_message),
                    Snackbar.LENGTH_LONG
                ).setAction(getString(R.string.settings)) {
                    val intent =
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                    startActivity(intent)
                }.show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        userPreferencesRepository = UserPreferencesRepository(applicationContext)
        lifecycleScope.launch {
            val theme = userPreferencesRepository.selectedTheme.first()
            if (AppCompatDelegate.getDefaultNightMode() != theme) {
                AppCompatDelegate.setDefaultNightMode(theme)
            }
        }
        super.onCreate(savedInstanceState)
        checkIfFirstTimeOpen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        updateStatusBarIconColor()
        initializeRecyclerView()
        initializePlayerControls()
        observeViewModel()
        checkAndRequestNotificationPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        lifecycleScope.launch {
            val selectedTheme = userPreferencesRepository.selectedTheme.first()
            when (selectedTheme) {
                THEME_LIGHT -> menu.findItem(R.id.action_theme_light)?.isChecked = true
                THEME_DARK -> menu.findItem(R.id.action_theme_dark)?.isChecked = true
                THEME_SYSTEM -> menu.findItem(R.id.action_theme_system)?.isChecked = true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val newNightMode = when (item.itemId) {
            R.id.action_theme_light -> THEME_LIGHT
            R.id.action_theme_dark -> THEME_DARK
            R.id.action_theme_system -> THEME_SYSTEM
            R.id.action_change_language -> {
                displayLanguageSelectionDialog()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
        lifecycleScope.launch {
            val currentStoredTheme = userPreferencesRepository.selectedTheme.first()
            if (currentStoredTheme != newNightMode) {
                applyThemeChange(newNightMode)
            }
        }
        return true
    }

    private fun applyThemeChange(themeMode: Int) {
        lifecycleScope.launch {
            userPreferencesRepository.updateSelectedTheme(themeMode)
            AppCompatDelegate.setDefaultNightMode(themeMode)
            recreate()
        }
    }
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.notification_permission_title))
                        .setMessage(getString(R.string.notification_permission_rationale))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }

                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun updateStatusBarIconColor() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isLightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
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
                    val fromPos = viewHolder.bindingAdapterPosition
                    val toPos = target.bindingAdapterPosition
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
                        controller.playWhenReady = true
                        controller.play()
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.select_aarti_and_noti_perm_reminder),
                                Snackbar.LENGTH_LONG
                            ).show()
                        } else {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.please_select_aarti_to_play),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        binding.playerControlsContainer.buttonNext.setOnClickListener { mediaController?.seekToNextMediaItem() }
        binding.playerControlsContainer.buttonPrevious.setOnClickListener { mediaController?.seekToPreviousMediaItem() }

        binding.playerControlsContainer.seekBarPlayer.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
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

        binding.playerControlsContainer.seekBarPlayer.max =
            state.totalDurationMs.toInt().coerceAtLeast(0)
        if (!binding.playerControlsContainer.seekBarPlayer.isPressed) {
            binding.playerControlsContainer.seekBarPlayer.progress =
                state.currentPositionMs.toInt().coerceAtLeast(0)
        }

        binding.playerControlsContainer.textViewCurrentTime.text =
            formatDuration(state.currentPositionMs)
        binding.playerControlsContainer.textViewTotalTime.text =
            formatDuration(state.totalDurationMs)

        playlistAdapter.updatePlaybackVisuals(state.currentAarti?.id, state.isPlaying)
        playlistAdapter.setUserInteractionEnabled(!state.isPlaying)

        val hasPlaylist = state.currentPlaylist.isNotEmpty()
        val controller = mediaController
        binding.playerControlsContainer.buttonNext.isEnabled =
            hasPlaylist && controller?.hasNextMediaItem() == true
        binding.playerControlsContainer.buttonPrevious.isEnabled =
            hasPlaylist && controller?.hasPreviousMediaItem() == true
        binding.playerControlsContainer.buttonPlayPause.isEnabled =
            hasPlaylist || controller?.isPlaying == true
    }


    private fun startOrUpdatePlayerProgressPoller() {
        stopPlayerProgressPoller()
        val currentMediaController = this.mediaController
        if (currentMediaController == null || !currentMediaController.isConnected || !currentMediaController.isPlaying) {
            return
        }
        playerProgressPoller = object : Runnable {
            override fun run() {
                val controllerInPoll = this@MainActivity.mediaController
                if (controllerInPoll != null && controllerInPoll.isConnected && controllerInPoll.isPlaying) {
                    viewModel.setPlayerState(
                        isPlaying = true,
                        currentAartiId = controllerInPoll.currentMediaItem?.mediaId?.toIntOrNull(),
                        positionMs = controllerInPoll.currentPosition,
                        durationMs = controllerInPoll.duration.coerceAtLeast(0L)
                    )
                    playerProgressHandler.postDelayed(this, POLLING_INTERVAL_MS)
                }
            }
        }
        playerProgressHandler.post(playerProgressPoller!!)
    }

    private fun stopPlayerProgressPoller() {
        playerProgressPoller?.let {
            playerProgressHandler.removeCallbacks(it)
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
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(getString(aarti.title)).build()
                    )
                    .build()
            }
            val currentControllerPlaylistIds = (0 until controller.mediaItemCount).mapNotNull {
                controller.getMediaItemAt(it).mediaId
            }
            val newPlaylistIds = media3Items.map { it.mediaId }

            if (currentControllerPlaylistIds != newPlaylistIds) {
                val wasPlayingBeforeUpdate = controller.isPlaying
                val playWhenReadyBeforeUpdate = controller.playWhenReady
                val previousPlaybackState = controller.playbackState
                val playerWasEffectivelyEmpty = currentControllerPlaylistIds.isEmpty() &&
                        (previousPlaybackState == Player.STATE_IDLE || previousPlaybackState == Player.STATE_ENDED)

                if (media3Items.isEmpty()) {
                    controller.stop()
                    controller.clearMediaItems()
                    viewModel.onPlaylistFinished()
                } else {
                    val resetPosition = playerWasEffectivelyEmpty
                    controller.setMediaItems(media3Items, resetPosition)

                    if (previousPlaybackState == Player.STATE_ENDED && !wasPlayingBeforeUpdate) {
                        controller.playWhenReady = false
                    } else {
                        controller.playWhenReady = playWhenReadyBeforeUpdate
                    }
                    if (controller.playbackState == Player.STATE_IDLE || (controller.playbackState == Player.STATE_ENDED && media3Items.isNotEmpty())) {
                        controller.prepare()
                    }
                    if (wasPlayingBeforeUpdate && controller.playWhenReady && !controller.isPlaying) {
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
            mediaController?.let { mc ->
                viewModel.setPlayerState(
                    isPlaying = mc.isPlaying,
                    currentAartiId = mc.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = mc.currentPosition,
                    durationMs = mc.duration.coerceAtLeast(0L)
                )
                if (mc.isPlaying) {
                    startOrUpdatePlayerProgressPoller()
                }
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
                viewModel.setPlayerState(
                    isPlaying = false,
                    currentAartiId = currentController.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = currentController.currentPosition,
                    durationMs = currentController.duration.coerceAtLeast(0L)
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
            val currentController = mediaController ?: return
            viewModel.setPlayerState(
                isPlaying = currentController.isPlaying,
                currentAartiId = mediaItem?.mediaId?.toIntOrNull(),
                positionMs = 0L,
                durationMs = currentController.duration.coerceAtLeast(0L)
            )
            if (currentController.isPlaying) {
                startOrUpdatePlayerProgressPoller()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateString = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN ($playbackState)"
            }
            val currentController = mediaController
            if (playbackState == Player.STATE_ENDED) {
                viewModel.onPlaylistFinished()
                stopPlayerProgressPoller()
            } else if (currentController != null) {
                viewModel.setPlayerState(
                    isPlaying = currentController.isPlaying,
                    currentAartiId = currentController.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = currentController.currentPosition,
                    durationMs = currentController.duration.coerceAtLeast(0L)
                )
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
            mediaController?.let {
                viewModel.setPlayerState(
                    isPlaying = it.isPlaying,
                    currentAartiId = it.currentMediaItem?.mediaId?.toIntOrNull(),
                    positionMs = newPosition.positionMs,
                    durationMs = it.duration.coerceAtLeast(0L)
                )
            }
        }
    }


    private fun displayLanguageSelectionDialog() {
        val languages =
            arrayOf(getString(R.string.language_english), getString(R.string.language_marathi))
        val languageCodes = arrayOf("en", "mr")
        val currentLangCode =
            AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: Locale.getDefault()
                .toLanguageTag().take(2)
        val checkedItem = languageCodes.indexOf(currentLangCode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language_title))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        languageCodes[which]
                    )
                )
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun checkIfFirstTimeOpen() {
        lifecycleScope.launch {
            val isFirstTime = userPreferencesRepository.isFirstTimeOpen.first()
            if (isFirstTime) {
                logFirstInstallationToFirestore() // New function
            }
        }
    }

    private suspend fun logFirstInstallationToFirestore() {
        try {
            // Get ANDROID_ID
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            // You can add more relevant data here
            val installationRecord = hashMapOf(
                "androidId" to androidId,
                "timestamp" to FieldValue.serverTimestamp(), // Uses server's time
                "deviceModel" to Build.MODEL,
                "deviceManufacturer" to Build.MANUFACTURER,
                "androidVersion" to Build.VERSION.RELEASE,
                "appVersionName" to packageManager.getPackageInfo(packageName, 0).versionName,
                "appVersionCode" to packageManager.getPackageInfo(packageName, 0).longVersionCode,
                "locale" to Locale.getDefault().toString()
            )
            val db: FirebaseFirestore = FirebaseFirestore.getInstance()
            val documentReference = db.collection(FIRESTORE_COLLECTION_INSTALLATIONS)
                .document(androidId)
            documentReference.set(installationRecord).await()
            userPreferencesRepository.updateFirstTimeOpen(false) // Set after showing dialog
        } catch (e: Exception) {
        }
    }
}