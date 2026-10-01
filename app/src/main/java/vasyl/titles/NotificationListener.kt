package vasyl.titles

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vasyl.titles.excludeapps.AppScope
import vasyl.titles.helpers.Helpers
import vasyl.titles.helpers.MarqueeDrawView
import vasyl.titles.receivers.PhoneListener
import vasyl.titles.widget.Lrc
import vasyl.titles.widget.updateWidgetFromService
import vasyl.titles.widget.updateWidgetPlayState
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the current track as an overlay (status bar area) and feeds the home screen widgets.
 *
 * Sources:
 *  - media sessions of regular players ([MediaController]),
 *  - the FYT player (com.syu.music), which has no media session and reports through [MusicService].
 *
 * Threading: everything that touches state or windows runs on the main thread. File access, bitmap
 * decoding and scaling run on [Dispatchers.IO] inside [scope], which only lives between
 * [onListenerConnected] and [onListenerDisconnected]; nothing can fire after a disconnect anymore.
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val PREFS = "savedPrefs"
        private const val EXCLUDE_PREFS = "ExcludeAppsPrefs"
        private const val EXCLUDE_KEY = "exclude_apps"

        /** Values accepted by [setStatus]. */
        const val MEDIA_SOURCE_ANY = 0
        const val MEDIA_SOURCE_FYT = 1
        const val MEDIA_SOURCE_CONTROLLER = 2

        const val SOURCE_FYT = "fyt"
        const val SOURCE_CONTROLLER = "mediaController"
        private const val FYT_PACKAGE = MusicService.MUSIC_PKG

        /** For these players the "artist" is the channel name, so only the title is displayed. */
        private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube", "app.revanced.android.youtube")

        private const val DEFAULT_STATUS_DELAY_MS = 2_000L
        private const val CONFIG_CHANGE_DELAY_MS = 500L
        private const val RECONNECT_CHECK_DELAY_MS = 15_000L
        private const val FYT_BLOCK_AFTER_CONTROLLER_MS = 2_500L
        private const val WATCHDOG_ACTIVE_MS = 700L
        private const val WATCHDOG_IDLE_MS = 2_000L
        private const val SESSION_CHECK_DEBOUNCE_MS = 250L
        private const val SESSION_RECHECK_MS = 1_000L
        private const val COVER_MAX_PX = 512
        private const val FALLBACK_ICON_PX = 256
        private const val COVER_CORNER_RATIO = 0.07f

        /** Settings preview shown when appearance settings change and no title is on screen. */
        private const val PREVIEW_DURATION_MS = 3_000L
        /** The preview text is made at least this much wider than the caption, so it scrolls. */
        private const val PREVIEW_MIN_WIDTH_FACTOR = 1.5f
        private const val PREVIEW_SEPARATOR = "  \u2022  "

        private const val OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        @Volatile @JvmStatic var source: String = ""
        @Volatile @JvmStatic var activeControllerPackage: String = ""
        @Volatile @JvmStatic var excludeForWidget: Boolean = false

        @Volatile private var instanceRef: WeakReference<NotificationListener>? = null
        @Volatile private var cacheTrimmed = false

        fun setInstance(instance: NotificationListener) {
            instanceRef = WeakReference(instance)
        }

        /** True while the system has the listener connected (checked by [ListenerGuard]). */
        @JvmStatic
        val isConnected: Boolean
            get() = instanceRef?.get()?.connected == true

        /** Re-evaluates the current player and shows it again. Safe to call from any thread. */
        @JvmStatic
        fun setDefaultStatusFromCompanion() {
            val service = instanceRef?.get() ?: return
            service.mainHandler.post { service.setDefaultStatus() }
        }

        /**
         * Appearance settings changed (font, colors, size, position, margin, width): the title on
         * screen is re-created with them right away; without one, a scrolling preview is shown for
         * three seconds. Safe to call from any thread.
         */
        @JvmStatic
        fun previewAppearance() {
            val service = instanceRef?.get() ?: return
            service.mainHandler.post { service.onAppearanceChanged() }
        }

        /**
         * Display settings changed (titles on/off, artist, FYT title source, excluded apps): the
         * current player is re-evaluated immediately instead of after the usual delay.
         */
        @JvmStatic
        fun refreshNow() {
            val service = instanceRef?.get() ?: return
            service.mainHandler.post { service.setDefaultStatus(0L) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State (main thread only)
    // ---------------------------------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val windowManager: WindowManager by lazy { getSystemService(WindowManager::class.java) }
    private val defaultCover: Bitmap by lazy { createDefaultCover() }

    private var scope: CoroutineScope? = null
    private var connected = false
    private var destroyed = false

    private var mediaSessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private var listenerComponent: ComponentName? = null
    private var sessionListenerRegistered = false
    private var playbackCallbackRegistered = false
    private var compatReceiverRegistered = false
    private val sessionListener = SessionListener(WeakReference(this))
    private val playbackCallback = PlaybackCallback(WeakReference(this))
    private val compatReceiver = CompatReceiver(WeakReference(this))

    // Overlay
    private var privileged = false
    private var overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var statusBarHeight = 0
    private var overlayView: View? = null
    private var overlaySpec: OverlaySpec? = null
    /** Track shown by [overlayView]; null when nothing or only the settings preview is on screen. */
    private var displayedContent: StatusContent? = null
    private var previewShowing = false
    private var previewJob: Job? = null

    // Media session source
    private var mediaController: MediaController? = null
    private var controllerCallback: ControllerCallback? = null
    private var meta: MediaMetadata? = null
    private var currentState: Int? = null
    private var songCur: String? = null
    private var songPrev: String? = null   // in memory only (was persisted and hid the overlay after restarts)
    private var prevState: Int = PlaybackState.STATE_STOPPED

    // FYT source
    private var fytState = false          // player reports "playing"
    private var fytStartPending = false   // FYT switched to "playing"; handled once the track is resolved
    private var fytSet = false            // current FYT track has been applied
    private var fytAllowed = true         // FYT updates are ignored shortly after another player started
    private var fytInfo: FytInfo? = null
    private var fytGeneration = 0
    private var fytTotalMinutes = 0L
    private var fytCurMinutes = 0L
    @Volatile private var fytMetadataCache: FytMetadata? = null

    // Last applied content
    private var song: String = ""
    private var artist: String = ""
    private var shouldExclude = false
    private var lastWidgetSong: String? = null
    private var lastWidgetArtist: String? = null
    private var lastSavedController: String? = null

    private var defaultStatusJob: Job? = null
    private var fytAllowedJob: Job? = null
    private var sessionCheckJob: Job? = null
    private var coverJob: Job? = null
    private var fytResolveJob: Job? = null

    /** Duration / position of the current track in milliseconds. */
    var totalMinutes: Long = 0
    var curMinutes: Long = 0

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        setInstance(this)
        updateWidgetPlayState(this, false)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (destroyed) return
        disconnectInternal() // tolerate a second connect without a disconnect in between

        val newScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, t -> Log.e(TAG, "Listener coroutine failed", t) }
        )
        scope = newScope
        connected = true

        listenerComponent = ComponentName(this, NotificationListener::class.java)
        privileged = Privileges.canUseSystemOverlay(this)
        overlayType = Privileges.overlayWindowType(this)
        statusBarHeight = readStatusBarHeight()
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        fytSet = false
        fytAllowed = true
        songPrev = null
        prevState = PlaybackState.STATE_STOPPED
        lastWidgetSong = null
        lastWidgetArtist = null

        registerSessionListener()
        registerPlaybackCallback()
        registerCompatReceiver()
        newScope.launch { FytPlayerBus.events.collect { onFytEvent(it) } }

        checkActiveSessions()
        setDefaultStatus(DEFAULT_STATUS_DELAY_MS)

        // Call state tracking. This used to be an explicit broadcast to PhoneStateBroadcastReceiver
        // (which registered one more listener each time) plus a broadcast to SecretCode that did
        // nothing without the secret code action.
        PhoneListener.register(this)

        startSessionWatchdog(newScope)
        trimCacheOnce()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        disconnectInternal()
        if (!destroyed) {
            // The system retries a lost binding only once; repair it if it does not come back.
            // (requestRebind() was used here before; it only undoes requestUnbind().)
            ListenerGuard.check(applicationContext, RECONNECT_CHECK_DELAY_MS)
        }
    }

    override fun onDestroy() {
        destroyed = true
        disconnectInternal()
        if (instanceRef?.get() === this) instanceRef = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!connected) return
        statusBarHeight = readStatusBarHeight()
        removeWindowView()
        setDefaultStatus(CONFIG_CHANGE_DELAY_MS)
    }

    private fun disconnectInternal() {
        connected = false
        scope?.cancel()
        scope = null
        defaultStatusJob = null
        fytAllowedJob = null
        sessionCheckJob = null
        coverJob = null
        fytResolveJob = null
        fytAllowed = true

        removeWindowView()
        unregisterCompatReceiver()
        unregisterPlaybackCallback()
        unregisterSessionListener()
        detachController()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------------------------------------
    // Registrations
    // ---------------------------------------------------------------------------------------------

    private fun registerSessionListener() {
        val manager = mediaSessionManager ?: return
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, listenerComponent, mainHandler)
            sessionListenerRegistered = true
        } catch (e: RuntimeException) { // SecurityException when notification access is missing
            Log.e(TAG, "Cannot register the active sessions listener", e)
        }
    }

    private fun unregisterSessionListener() {
        if (!sessionListenerRegistered) return
        sessionListenerRegistered = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot remove the active sessions listener", e)
        }
    }

    private fun registerPlaybackCallback() {
        val manager = audioManager ?: return
        try {
            manager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            playbackCallbackRegistered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot register the audio playback callback", e)
        }
    }

    private fun unregisterPlaybackCallback() {
        if (!playbackCallbackRegistered) return
        playbackCallbackRegistered = false
        try {
            audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot unregister the audio playback callback", e)
        }
    }

    /**
     * Legacy in-app broadcasts ("titlesReceiver" / "removeReceiver"). The receiver is NOT exported
     * anymore, so only this app (and the system UID) can reach it. MusicService uses [FytPlayerBus].
     */
    private fun registerCompatReceiver() {
        val filter = IntentFilter().apply {
            addAction(MusicService.TITLES_RECEIVER)
            addAction(MusicService.REMOVE_RECEIVER)
        }
        try {
            ContextCompat.registerReceiver(this, compatReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            compatReceiverRegistered = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot register the compat receiver", e)
        }
    }

    private fun unregisterCompatReceiver() {
        if (!compatReceiverRegistered) return
        compatReceiverRegistered = false
        try {
            unregisterReceiver(compatReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Compat receiver was not registered", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Media sessions
    // ---------------------------------------------------------------------------------------------

    /**
     * Re-reads the active sessions and switches to the one that is playing. Replaces the old 10 ms
     * polling loop: it is triggered by [AudioManager.AudioPlaybackCallback] and a slow watchdog.
     */
    fun checkActiveSessions() {
        if (!connected) return
        val controllers = try {
            mediaSessionManager?.getActiveSessions(listenerComponent)
        } catch (e: RuntimeException) { // SecurityException while access is being revoked
            Log.w(TAG, "getActiveSessions failed", e)
            null
        }
        onSessionsChanged(controllers)
    }

    private fun onSessionsChanged(controllers: List<MediaController>?) {
        if (!connected || controllers.isNullOrEmpty()) return
        val candidate = pickController(controllers) ?: return
        val current = mediaController
        if (current != null && current.sessionToken == candidate.sessionToken) return

        detachController()
        if (!fytState) hideTitle()
        attachController(candidate)
    }

    private fun pickController(controllers: List<MediaController>): MediaController? =
        controllers.firstOrNull { playbackStateOf(it)?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

    private fun attachController(controller: MediaController) {
        mediaController = controller
        meta = null
        currentState = null
        songPrev = null // make sure the new player is displayed
        val callback = ControllerCallback(WeakReference(this), controller.sessionToken)
        controllerCallback = callback
        try {
            controller.registerCallback(callback, mainHandler)
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerCallback failed for ${controller.packageName}", e)
        }
        metadataOf(controller)?.let { onControllerMetadataChanged(it) }
        playbackStateOf(controller)?.let { onControllerPlaybackStateChanged(it) }
    }

    private fun detachController() {
        val controller = mediaController
        val callback = controllerCallback
        if (controller != null && callback != null) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "unregisterCallback failed", e)
            }
        }
        mediaController = null
        controllerCallback = null
        meta = null
        currentState = null
    }

    private fun isCurrent(token: MediaSession.Token): Boolean =
        connected && mediaController?.sessionToken == token

    private fun onControllerSessionDestroyed() {
        if (!fytState) {
            hideTitle()
            updateWidgetPlayState(this, false)
        }
        detachController()
        checkActiveSessions() // attach the next session, if there is one
    }

    private fun onControllerMetadataChanged(metadata: MediaMetadata?) {
        Helpers.counter = 0
        meta = metadata
        set()
    }

    private fun onControllerPlaybackStateChanged(state: PlaybackState?) {
        val newState = state?.state
        currentState = newState
        when (newState) {
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_BUFFERING -> {
                prevState = newState
                Helpers.counter = 0
                if (!fytState) {
                    hideTitle()
                    updateWidgetPlayState(this, false)
                }
            }

            PlaybackState.STATE_PLAYING -> {
                set()
                updateWidgetPlayState(this, true)
            }
        }
    }

    /** Decides whether the playing media session track has to be (re)displayed. */
    private fun set() {
        when (currentState) {
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> Helpers.counter = 0

            PlaybackState.STATE_PLAYING -> {
                val metadata = meta
                // Live streams report duration 0 (YouTube live would re-add the view every second).
                val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                // Keep the previous title if the new metadata has none (prevents flickering).
                metadata.titleOrNull()?.let { songCur = it }

                val changed = songCur != songPrev ||
                    prevState == PlaybackState.STATE_STOPPED ||
                    prevState == PlaybackState.STATE_PAUSED ||
                    prevState == PlaybackState.STATE_BUFFERING
                if (!changed) return

                songPrev = songCur
                prevState = PlaybackState.STATE_PLAYING
                if (duration != 0L) {
                    setStatus(MEDIA_SOURCE_CONTROLLER)
                } else if (Helpers.counter == 0) { // live: only once until metadata / state changes
                    Helpers.counter++
                    curMinutes = 0
                    setStatus(MEDIA_SOURCE_CONTROLLER)
                } else {
                    hideTitle()
                }
            }
        }
    }

    private fun startSessionWatchdog(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val musicActive = audioManager?.isMusicActive == true
                // Players that start while another session is paused do not always trigger
                // onActiveSessionsChanged; the FYT player sometimes makes us lose the session.
                if (musicActive && !PhoneListener.CALLING && (overlayView == null || fytState)) {
                    checkActiveSessions()
                }
                delay(if (musicActive) WATCHDOG_ACTIVE_MS else WATCHDOG_IDLE_MS)
            }
        }
    }

    private fun scheduleSessionCheck() {
        val scope = scope ?: return
        if (PhoneListener.CALLING) return
        sessionCheckJob?.cancel()
        sessionCheckJob = scope.launch {
            delay(SESSION_CHECK_DEBOUNCE_MS)
            checkActiveSessions()
            delay(SESSION_RECHECK_MS) // players often update their session after starting audio
            checkActiveSessions()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // FYT player
    // ---------------------------------------------------------------------------------------------

    private fun onFytEvent(event: FytPlayerBus.Event) {
        if (!connected) return
        when (event) {
            is FytPlayerBus.Event.Data -> onFytData(event.track)
            FytPlayerBus.Event.Remove -> onFytRemove()
        }
    }

    private fun onFytData(track: FytTrack) {
        if (track.playing && !fytState) fytStartPending = true
        fytState = track.playing
        if (!track.playing) fytStartPending = false
        fytCurMinutes = track.positionMs
        if (track.path.isBlank()) return
        val scope = scope ?: return

        val generation = ++fytGeneration
        fytResolveJob?.cancel()
        fytResolveJob = scope.launch {
            val info = withContext(Dispatchers.IO) { resolveFytInfo(track) } ?: return@launch
            if (generation == fytGeneration) applyFytUpdate(info)
        }
    }

    private fun applyFytUpdate(info: FytInfo) {
        fytInfo = info
        fytTotalMinutes = info.durationMs

        // FYT has priority when it STARTS playing: the other player is paused once. The FYT player
        // keeps reporting "playing" for a moment after another app took the audio focus (it pauses
        // itself right after); those late updates used to pause the app that had just started.
        // Within the handover window (fytAllowed == false) the start is dropped entirely.
        if (fytStartPending && fytState) {
            fytStartPending = false
            if (fytAllowed) pauseOtherPlayer()
        }

        // Another track than the one on screen -> it has to be applied again.
        if (song != info.title && song != info.fileName) fytSet = false

        if (fytState && !fytSet && fytAllowed) {
            fytSet = true
            if (currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED) {
                hideTitle()
            }
            updateWidgetPlayState(this, true)
            setStatus(MEDIA_SOURCE_FYT)
        }
        if (!fytState && fytSet) {
            fytSet = false
            if (currentState != PlaybackState.STATE_PLAYING) {
                hideTitle()
                updateWidgetPlayState(this, false)
            }
        }
    }

    private fun onFytRemove() {
        fytState = false // was never reset before, so a stopped FYT player kept "priority"
        fytStartPending = false
        fytSet = false
        if (currentState != PlaybackState.STATE_PLAYING) {
            hideTitle()
            updateWidgetPlayState(this, false)
        }
    }

    private fun pauseOtherPlayer() {
        val controller = mediaController ?: return
        if (currentState != PlaybackState.STATE_PLAYING) return
        if (controller.packageName == FYT_PACKAGE) return
        try {
            controller.transportControls.pause()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot pause ${controller.packageName}", e)
        }
    }

    private fun blockFytTemporarily() {
        fytAllowed = false
        fytAllowedJob?.cancel()
        val scope = scope
        if (scope == null) {
            fytAllowed = true
            return
        }
        fytAllowedJob = scope.launch {
            delay(FYT_BLOCK_AFTER_CONTROLLER_MS)
            fytAllowed = true
        }
    }

    /** Runs on [Dispatchers.IO]. Returns null when the file does not exist (same as before). */
    private fun resolveFytInfo(track: FytTrack): FytInfo? {
        val file = File(track.path)
        if (!file.isFile) return null

        val lastModified = file.lastModified()
        val length = file.length()
        val cached = fytMetadataCache
        val metadata = if (cached != null && cached.path == track.path &&
            cached.lastModified == lastModified && cached.length == length
        ) {
            cached
        } else {
            readFytMetadata(file, lastModified, length).also { fytMetadataCache = it }
        }

        val fileName = file.nameWithoutExtension.ifBlank { file.name }
        return FytInfo(
            path = track.path,
            // Title fallback: tags -> title reported by the player -> file name. Before, a file
            // without a title tag was never displayed.
            title = metadata.title.validTag() ?: track.title.validTag() ?: fileName,
            artist = metadata.artist.validTag() ?: track.artist.validTag() ?: "",
            album = metadata.album.validTag() ?: track.album.validTag() ?: "",
            fileName = fileName,
            durationMs = metadata.durationMs.takeIf { it > 0 } ?: track.durationMs,
            picture = metadata.picture,
        )
    }

    private fun readFytMetadata(file: File, lastModified: Long, length: Long): FytMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                FileInputStream(file).use { input -> retriever.setDataSource(input.fd, 0, length) }
            } catch (e: IOException) {
                retriever.setDataSource(file.absolutePath)
            } catch (e: RuntimeException) {
                retriever.setDataSource(file.absolutePath)
            }
            return FytMetadata(
                path = file.path,
                lastModified = lastModified,
                length = length,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                picture = retriever.embeddedPicture,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read metadata of ${file.path}", e)
            return FytMetadata(file.path, lastModified, length, null, null, null, 0L, null)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "MediaMetadataRetriever.release failed", e)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Status (overlay + widget)
    // ---------------------------------------------------------------------------------------------

    private fun setDefaultStatus(delayMs: Long = DEFAULT_STATUS_DELAY_MS) {
        val scope = scope ?: return
        defaultStatusJob?.cancel()
        defaultStatusJob = scope.launch {
            delay(delayMs)
            val controller = mediaController
            val controllerPlaying = controller != null &&
                playbackStateOf(controller)?.state == PlaybackState.STATE_PLAYING
            if (controller != null && controllerPlaying) {
                val metadata = metadataOf(controller)
                if (metadata != null) {
                    meta = metadata
                    currentState = PlaybackState.STATE_PLAYING
                    setStatus(MEDIA_SOURCE_CONTROLLER)
                }
            } else {
                // The last FYT state is only restored when no media session is playing. It can be
                // stale (e.g. the FYT player was killed while playing and never sent "stopped");
                // replaying it would pause the player that actually plays and keep fytState stuck.
                // Live FYT updates still take priority through onFytData().
                val lastFyt = FytPlayerBus.last.value
                if (lastFyt != null && lastFyt.playing) {
                    fytSet = false
                    onFytData(lastFyt)
                }
            }
        }
    }

    /**
     * Shows the current track of the given source ([MEDIA_SOURCE_FYT], [MEDIA_SOURCE_CONTROLLER] or
     * [MEDIA_SOURCE_ANY]). Non-blocking: the overlay is added immediately, the cover for the widget
     * is loaded in the background.
     */
    fun setStatus(mediaSource: Int) {
        if (!connected) return
        if (mediaSource == MEDIA_SOURCE_CONTROLLER) {
            fytState = false
            fytSet = true
        }
        val content = when {
            fytState && fytAllowed && (mediaSource == MEDIA_SOURCE_ANY || mediaSource == MEDIA_SOURCE_FYT) ->
                buildFytContent()

            !fytState && (mediaSource == MEDIA_SOURCE_ANY || mediaSource == MEDIA_SOURCE_CONTROLLER) ->
                buildControllerContent()

            else -> null
        } ?: return // nothing valid to show (previously stale text was displayed)
        applyContent(content)
    }

    private fun buildFytContent(): StatusContent? {
        val info = fytInfo ?: return null
        val byFileName = prefs.getInt("fytData", 1) == 2
        val songText = if (byFileName) info.fileName else info.title
        val artistText = when {
            byFileName -> ""
            info.artist.isBlank() || info.artist == "Unknown" -> info.album
            else -> info.artist
        }
        return StatusContent(
            source = SOURCE_FYT,
            packageName = FYT_PACKAGE,
            song = songText,
            artist = artistText,
            totalMs = info.durationMs.takeIf { it > 0 } ?: fytTotalMinutes,
            positionMs = fytCurMinutes,
            roundCover = true,
            loadCover = { loadFytCover(info) },
        )
    }

    private fun buildControllerContent(): StatusContent? {
        val controller = mediaController ?: return null
        val metadata = meta ?: metadataOf(controller) ?: return null
        val title = metadata.titleOrNull() ?: return null
        val artistText = ARTIST_KEYS.firstNotNullOfOrNull { key -> metadata.getString(key)?.trim()?.ifEmpty { null } }
            ?: ""
        val total = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = if (total == 0L) 0L else playbackStateOf(controller)?.position ?: 0L
        val packageName = controller.packageName
        return StatusContent(
            source = SOURCE_CONTROLLER,
            packageName = packageName,
            song = title,
            artist = artistText,
            totalMs = total,
            positionMs = position,
            roundCover = false,
            loadCover = { loadControllerCover(metadata, packageName) },
        )
    }

    private fun applyContent(content: StatusContent) {
        song = content.song
        artist = content.artist
        source = content.source
        totalMinutes = content.totalMs
        curMinutes = content.positionMs
        shouldExclude = isExcludedPackage(content.packageName)
        if (content.source == SOURCE_CONTROLLER) {
            activeControllerPackage = content.packageName
            blockFytTemporarily() // a late FYT update must not override the player that just started
        }
        rememberLastController(content.packageName)

        val displayTitles = prefs.getBoolean("titles_box", true)
        if (displayTitles && !shouldExclude && content.song.isNotBlank()) {
            showOverlay(content)
        } else {
            hideTitle()
        }

        excludeForWidget = prefs.getBoolean("exclude_box", true) && shouldExclude
        if (!excludeForWidget && content.song.isNotBlank() &&
            (content.song != lastWidgetSong || content.artist != lastWidgetArtist)
        ) {
            lastWidgetSong = content.song
            lastWidgetArtist = content.artist
            updateWidgetAsync(content)
        }
    }

    private fun updateWidgetAsync(content: StatusContent) {
        val scope = scope ?: return
        val appContext = applicationContext
        coverJob?.cancel()
        coverJob = scope.launch {
            val cover = withContext(Dispatchers.IO) {
                val loaded = try {
                    content.loadCover()
                } catch (e: Exception) {
                    Log.w(TAG, "Cover loading failed", e)
                    null
                }
                when {
                    loaded == null -> defaultCover
                    content.roundCover -> roundCorners(loaded)
                    else -> loaded
                }
            }
            updateWidgetFromService(appContext, content.song, content.artist, cover)
        }
    }

    /** Shows [content] with the current settings (no-op if exactly this is already on screen). */
    private fun showOverlay(content: StatusContent) {
        val style = readOverlayStyle()
        if (!addOverlay(style, buildDisplayText(content, style.displayArtist))) return
        displayedContent = content
        // A real title always replaces the settings preview (e.g. a track change during it).
        previewShowing = false
        previewJob?.cancel()
        previewJob = null
    }

    /**
     * Applies changed appearance settings immediately. The title on screen is re-created with them;
     * when nothing is shown, a preview wider than the caption (so it scrolls) appears for
     * [PREVIEW_DURATION_MS]. Changing settings again restarts the preview.
     */
    private fun onAppearanceChanged() {
        if (!connected) return
        val content = displayedContent
        if (content != null && overlayView != null) {
            showOverlay(content)
        } else {
            showPreview()
        }
    }

    private fun showPreview() {
        val scope = scope ?: return
        if (!prefs.getBoolean("titles_box", true)) return // titles are switched off: nothing to preview
        val style = readOverlayStyle()
        if (!addOverlay(style, previewText(style))) return
        displayedContent = null
        previewShowing = true
        previewJob?.cancel() // still running if the preview on screen was identical (restart the timer)
        previewJob = scope.launch {
            delay(PREVIEW_DURATION_MS)
            previewJob = null
            if (previewShowing) removeWindowView()
        }
    }

    /** Sample text repeated until it is at least [PREVIEW_MIN_WIDTH_FACTOR] x the caption width. */
    private fun previewText(style: OverlayStyle): String {
        val unit = getString(R.string.overlay_preview_text) + PREVIEW_SEPARATOR
        val unitWidth = MarqueeDrawView.measureText(this, unit, style.size.toFloat(), style.typeface, style.ttfFile)
        val repeats = if (unitWidth > 0f) ceil(style.width * PREVIEW_MIN_WIDTH_FACTOR / unitWidth).toInt() else 1
        return unit.repeat(repeats.coerceIn(1, 50)).removeSuffix(PREVIEW_SEPARATOR)
    }

    private fun readOverlayStyle(): OverlayStyle {
        val res = resources
        val landscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val screenWidth = res.displayMetrics.widthPixels
        val marginDefault = prefs.getInt("marginPercentage", (screenWidth * 0.1275).toInt())
        val widthDefault = prefs.getInt("widthPercentage", (screenWidth * 0.45).toInt())
        val marginLeft = prefs.getInt(if (landscape) "margin_landscape" else "margin_portrait", marginDefault)
        val width = prefs.getInt(if (landscape) "width_landscape" else "width_portrait", widthDefault)
            .coerceAtLeast(1)
        val up = prefs.getInt("up", 0)
        val down = prefs.getInt("down", 0)
        val size = prefs.getInt("size", 16)
        var typeface = prefs.getInt("typeface", 0)
        val ttfUp = prefs.getInt("ttf_up", 0)
        val ttfDown = prefs.getInt("ttf_down", 0)

        var ttfFile: File? = null
        if (typeface == 3) {
            val file = prefs.getString("typeface_ttf", null)?.let(::File)
            if (file != null && file.isFile) {
                ttfFile = file
            } else {
                typeface = 0
                prefs.edit { putInt("typeface", 0) }
            }
        }

        val height = when {
            ttfFile != null -> statusBarHeight + size * 2.5f
            size > 22 -> (statusBarHeight + size).toFloat()
            else -> statusBarHeight.toFloat()
        }.roundToInt().coerceAtLeast(1)

        return OverlayStyle(
            width = width,
            height = height,
            marginLeft = marginLeft,
            yOffset = when {
                down > 0 -> down
                up > 0 -> -up
                else -> 0
            },
            size = size,
            typeface = typeface,
            ttfFile = ttfFile,
            ttfOffset = when {
                ttfDown > 0 -> ttfDown.toFloat()
                ttfUp > 0 -> -ttfUp.toFloat()
                else -> 0f
            },
            textColor = parseColorOr(prefs.getString("color", "#FFFFFF"), Color.WHITE),
            bgColor = parseColorOr(prefs.getString("bg_color", "transparent"), Color.TRANSPARENT),
            displayArtist = prefs.getBoolean("artist_box", true),
        )
    }

    /**
     * Puts [text] with [style] on screen, replacing whatever overlay is shown.
     * @return true if exactly this is on screen afterwards
     */
    private fun addOverlay(style: OverlayStyle, text: String): Boolean {
        val pixelFormat = if (Color.alpha(style.bgColor) == 0) PixelFormat.RGBA_8888 else PixelFormat.OPAQUE
        val params = WindowManager.LayoutParams(style.width, style.height, overlayType, OVERLAY_FLAGS, pixelFormat).apply {
            gravity = Gravity.TOP or Gravity.START
            x = style.marginLeft
            y = style.yOffset
            title = "DisplayMediaTitles"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        Privileges.applyTouchPassThrough(this, params, privileged)

        val spec = OverlaySpec(
            text, style.width, style.height, style.marginLeft, style.yOffset, overlayType, style.textColor,
            style.bgColor, style.size, style.typeface, style.ttfFile?.path, style.ttfOffset, params.alpha
        )
        if (overlayView != null && spec == overlaySpec) return true // exactly this is already on screen
        removeWindowView()

        val view = MarqueeDrawView(applicationContext).apply {
            setText(text)
            setTextColor(style.textColor)
            setTextSizeSp(style.size.toFloat())
            setBgColorInt(style.bgColor, 0f)
            if (style.ttfFile != null) {
                setTypefaceFile(style.ttfFile)
                translationY = style.ttfOffset
            } else {
                setTypefaceFile(null)
                setTypefaceMode(style.typeface)
            }
            enableScroll(true)
            background = null
            alpha = 1f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        return try {
            windowManager.addView(view, params)
            overlayView = view
            overlaySpec = spec
            true
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay rejected (missing overlay permission?)", e)
            false
        } catch (e: RuntimeException) { // SecurityException, IllegalStateException
            Log.e(TAG, "Cannot add the overlay", e)
            false
        }
    }

    private fun buildDisplayText(content: StatusContent, displayArtist: Boolean): String {
        val artistText = content.artist
        val withArtist = displayArtist &&
            artistText.isNotBlank() &&
            artistText != "Unknown" &&
            content.packageName !in YOUTUBE_PACKAGES &&
            !content.song.contains(artistText)
        val base = if (withArtist) {
            getString(R.string.artist_and_song_str, artistText, content.song)
        } else {
            getString(R.string.song_str, content.song)
        }
        return base + getString(R.string.space)
    }

    /** Removes whatever overlay is shown, including the settings preview. */
    fun removeWindowView() {
        previewShowing = false
        previewJob?.cancel()
        previewJob = null
        displayedContent = null
        val view = overlayView ?: return
        overlayView = null
        overlaySpec = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay was not attached", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot remove the overlay", e)
        }
    }

    /**
     * Removes the track title because of a playback state change. A settings preview stays until
     * its timer ends or a real title replaces it.
     */
    private fun hideTitle() {
        if (previewShowing) return
        removeWindowView()
    }

    // ---------------------------------------------------------------------------------------------
    // Covers (called on Dispatchers.IO)
    // ---------------------------------------------------------------------------------------------

    private fun loadFytCover(info: FytInfo): Bitmap? {
        val bytes = info.picture
            ?: try {
                Lrc().getId3Info(info.path).dataPic
            } catch (e: Exception) {
                Log.w(TAG, "ID3 cover parsing failed for ${info.path}", e)
                null
            }
        return bytes?.takeIf { it.isNotEmpty() }?.let { decodeSampled(it, COVER_MAX_PX) }
    }

    private fun loadControllerCover(metadata: MediaMetadata, packageName: String): Bitmap? {
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) return scaleDown(art, COVER_MAX_PX)
        return loadAppIcon(packageName)
    }

    private fun loadAppIcon(packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        val pm = packageManager
        return try {
            @Suppress("DEPRECATION") // the ApplicationInfoFlags overload needs API 33
            val appInfo = pm.getApplicationInfo(packageName, 0)
            var icon: Drawable? = null
            if (appInfo.icon != 0) {
                try {
                    val appResources = pm.getResourcesForApplication(appInfo)
                    for (density in intArrayOf(640, 480, 320, 240, 160)) { // xxxhdpi .. mdpi
                        icon = try {
                            appResources.getDrawableForDensity(appInfo.icon, density, null)
                        } catch (e: Resources.NotFoundException) {
                            null
                        }
                        if (icon != null) break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No high resolution icon for $packageName", e)
                }
            }
            drawableToBitmap(icon ?: pm.getApplicationIcon(appInfo))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun createDefaultCover(): Bitmap {
        val drawable = ContextCompat.getDrawable(this, R.drawable.music_album_def)
        return drawable?.let { drawableToBitmap(it) }
            ?: Bitmap.createBitmap(FALLBACK_ICON_PX, FALLBACK_ICON_PX, Bitmap.Config.ARGB_8888)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { return scaleDown(it, COVER_MAX_PX) }
        }
        // Intrinsic size is -1 for color drawables and some adaptive icons.
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        val scale = min(1f, COVER_MAX_PX.toFloat() / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }

    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleDown(decoded, maxPx)
    }

    /** Returns a software bitmap whose longer side is at most [maxPx]. */
    private fun scaleDown(source: Bitmap, maxPx: Int): Bitmap {
        val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
        } else {
            source
        }
        val longest = max(software.width, software.height)
        if (longest <= maxPx) return software
        val scale = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            software,
            (software.width * scale).roundToInt().coerceAtLeast(1),
            (software.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun roundCorners(bitmap: Bitmap): Bitmap = try {
        val width = bitmap.width
        val height = bitmap.height
        val radius = min(width, height) * COVER_CORNER_RATIO
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.BLACK }
        val rect = Rect(0, 0, width, height)
        canvas.drawRoundRect(RectF(rect), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        output
    } catch (e: RuntimeException) {
        bitmap
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun metadataOf(controller: MediaController): MediaMetadata? = try {
        controller.metadata
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot read metadata of ${controller.packageName}", e)
        null
    }

    private fun playbackStateOf(controller: MediaController): PlaybackState? = try {
        controller.playbackState
    } catch (e: RuntimeException) { // IllegalArgumentException when a custom action fails to unparcel
        Log.w(TAG, "Cannot read playback state of ${controller.packageName}", e)
        null
    }

    private fun isExcludedPackage(packageName: String): Boolean =
        getSharedPreferences(EXCLUDE_PREFS, MODE_PRIVATE)
            .getStringSet(EXCLUDE_KEY, emptySet())
            ?.contains(packageName) == true

    private fun rememberLastController(packageName: String) {
        if (packageName.isBlank() || packageName == lastSavedController) return
        lastSavedController = packageName
        prefs.edit { putString("lastMediaController", packageName) }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun readStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun trimCacheOnce() {
        if (cacheTrimmed) return
        cacheTrimmed = true
        // Previously the whole cache was deleted on every status change (on the main thread).
        val dirs = listOfNotNull(cacheDir, externalCacheDir)
        AppScope.launch(Dispatchers.IO) {
            dirs.forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------------------------

    private class StatusContent(
        val source: String,
        val packageName: String,
        val song: String,
        val artist: String,
        val totalMs: Long,
        val positionMs: Long,
        val roundCover: Boolean,
        val loadCover: () -> Bitmap?,
    )

    private class FytMetadata(
        val path: String,
        val lastModified: Long,
        val length: Long,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val picture: ByteArray?,
    )

    private class FytInfo(
        val path: String,
        val title: String,
        val artist: String,
        val album: String,
        val fileName: String,
        val durationMs: Long,
        val picture: ByteArray?,
    )

    private class OverlayStyle(
        val width: Int,
        val height: Int,
        val marginLeft: Int,
        val yOffset: Int,
        val size: Int,
        val typeface: Int,
        val ttfFile: File?,
        val ttfOffset: Float,
        val textColor: Int,
        val bgColor: Int,
        val displayArtist: Boolean,
    )

    private data class OverlaySpec(
        val text: String,
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val type: Int,
        val textColor: Int,
        val bgColor: Int,
        val textSize: Int,
        val typeface: Int,
        val ttfPath: String?,
        val ttfOffset: Float,
        val alpha: Float,
    )

    /** Callbacks hold only weak references, so the system cannot leak the service through them. */
    private class ControllerCallback(
        private val ref: WeakReference<NotificationListener>,
        private val token: MediaSession.Token,
    ) : MediaController.Callback() {

        override fun onSessionDestroyed() {
            val service = ref.get() ?: return
            if (service.isCurrent(token)) service.onControllerSessionDestroyed()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val service = ref.get() ?: return
            if (service.isCurrent(token)) service.onControllerMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val service = ref.get() ?: return
            if (service.isCurrent(token)) service.onControllerPlaybackStateChanged(state)
        }
    }

    private class SessionListener(
        private val ref: WeakReference<NotificationListener>,
    ) : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            ref.get()?.onSessionsChanged(controllers)
        }
    }

    private class PlaybackCallback(
        private val ref: WeakReference<NotificationListener>,
    ) : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            ref.get()?.scheduleSessionCheck()
        }
    }

    private class CompatReceiver(
        private val ref: WeakReference<NotificationListener>,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ref.get() == null) return
            when (intent.action) {
                MusicService.TITLES_RECEIVER -> {
                    val extras = intent.extras ?: return
                    FytPlayerBus.publish(
                        FytTrack(
                            playing = extras.getBoolean(MusicService.PLAY_STATE, false),
                            title = extras.getString(MusicService.TITLE).orEmpty(),
                            artist = extras.getString(MusicService.PLAY_ARTIST).orEmpty(),
                            album = extras.getString(MusicService.PLAY_ALBUM).orEmpty(),
                            path = extras.getString(MusicService.PLAY_PATH).orEmpty(),
                            durationMs = extras.getLong(MusicService.PLAY_TOTALMINUTES, 0L),
                            positionMs = extras.getLong(MusicService.PLAY_CURMINUTES, 0L),
                        )
                    )
                }

                MusicService.REMOVE_RECEIVER -> FytPlayerBus.publishRemove()
            }
        }
    }
}

private val ARTIST_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_ARTIST,
    MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
    MediaMetadata.METADATA_KEY_AUTHOR,
    MediaMetadata.METADATA_KEY_WRITER,
    MediaMetadata.METADATA_KEY_COMPOSER,
    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
)

private fun MediaMetadata?.titleOrNull(): String? {
    if (this == null) return null
    return getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null }
        ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim()?.ifEmpty { null }
}

/** Trimmed tag value, or null for empty / placeholder values. */
private fun String?.validTag(): String? {
    val value = this?.trim() ?: return null
    if (value.isEmpty() || value == "null" || value.equals("Unknown", ignoreCase = true) ||
        value.equals("<unknown>", ignoreCase = true)
    ) {
        return null
    }
    return value
}

private fun parseColorOr(value: String?, fallback: Int): Int {
    if (value.isNullOrBlank()) return fallback
    if (value.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
    return try {
        Color.parseColor(value)
    } catch (e: IllegalArgumentException) {
        fallback
    }
}
