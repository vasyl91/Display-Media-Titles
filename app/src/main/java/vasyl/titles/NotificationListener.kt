package vasyl.titles

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import vasyl.titles.helpers.MarqueeDrawView
import vasyl.titles.widget.Lrc
import vasyl.titles.widget.updateWidgetFromService
import vasyl.titles.widget.updateWidgetPlayState
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class NotificationListener : NotificationListenerService() {
    
    private lateinit var sessionListener: SessionListener
    private lateinit var parameters: WindowManager.LayoutParams
    private var contextRef = WeakReference<Context>(null)    
    private var handler: Handler? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var settings: SharedPreferences? = null
    private var windowManager: WindowManager? = null
    
    private var up: Int = 0
    private var down: Int = 0
    private var ttfUp: Float = 0.0f
    private var ttfDown: Float = 0.0f
    private var size: Int = 16
    private var width: Int = 900
    private var marginLeft: Int = 255
    private var typefaceInt: Int = 0
    private var overlayParam: Int = 0
    private var flagParam: Int = 0
    private var statusBarHeight: Int = 0
    private var song: String? = ""
    private var songCur: String? = ""
    private var artist: String? = ""
    private var lastWidgetSong: String? = null
    private var lastWidgetArtist: String? = null
    private var displayArtist: Boolean = true
    private var displayTitles: Boolean = true
    private var statusColor = "#FFFFFF"
    private var statusBgColor = "transparent"
    private var paused: Boolean = false
    private var started: Boolean = false
    private var controllers: MutableList<MediaController>? = null
    private var displayedText: String = ""
    private var overlayView: View? = null
    private var marqueeTextView: TextView? = null
    private val addedViews = mutableListOf<View?>()
    private var mediaController: MediaController? = null
    private var meta: MediaMetadata? = null
    private var mState: Int? = 0
    private var currentState: Int? = 0
    private var count: Int = 0
    private var componentName: ComponentName? = null

    private var fytState: Boolean = false
    private var fytSet: Boolean = false
    private var musicName: String? = ""
    private var authorName: String? = ""
    private var pathName = ""
    private var album: String? = ""
    private var path: String? = ""
    private var fytData: Int = 1
    private var fytAllowed: Boolean = true // FYT sometimes updates data with some delay. This Boolean exist to not to interrupt changed media source.   

    var displayUI: Boolean = true

    private var isReceiverRegistered = false
    private var isSessionListenerRegistered = false
    private var isCleanedUp = false
    private val destroyed = AtomicBoolean(false)

    private lateinit var albumCover: Bitmap
    var totalMinutes: Long = 0
    var curMinutes: Long = 0
    var controllerTotalMinutes: Long = 0
    var controllerCurMinutes: Long = 0
    var fytTotalMinutes: Long = 0
    var fytCurMinutes: Long = 0
    var musicState: String? = ""
    var musicNamePrev: String = ""
    var prevCurFyt: Long = 0
    var prevMinutes: Long = 0
    var shouldExclude = false
    
    companion object {
        var source: String = ""
        var activeControllerPackage = ""
        var excludeForWidget: Boolean = false
        private var instanceRef: WeakReference<NotificationListener>? = null

        fun setInstance(instance: NotificationListener) {
            instanceRef = WeakReference(instance)
        }
        
        fun setDefaultStatusFromCompanion() {
            instanceRef?.get()?.setDefaultStatus()
        }
    }

    override fun onCreate() {  
        super.onCreate()
        setInstance(this)
        updateWidgetPlayState(this, false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onListenerConnected() {    
        super.onListenerConnected()  
        isCleanedUp = false
        contextRef = WeakReference(this)

        componentName = ComponentName(this, NotificationListener::class.java)

        settings = getSharedPreferences("savedPrefs", 0)    
        displayUI = settings!!.getBoolean("UI", true)
        fytData = settings!!.getInt("fytData", 1)

        paused = false
        
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) statusBarHeight = resources.getDimensionPixelSize(resourceId)

        if (imContextSystem(this)) {
            overlayParam = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
            flagParam = WindowManager.LayoutParams.TYPE_WALLPAPER
        } else {
            overlayParam = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flagParam = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionListener = SessionListener(this)
        if (!destroyed.get() && !isSessionListenerRegistered) {
            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
                isSessionListenerRegistered = true
            } catch (e: Exception) {
                Log.e("NotificationListener", "Error registering session listener: ${e.message}")
            }
        }
        controllers = mediaSessionManager?.getActiveSessions(componentName)
        mediaController = pickController(controllers)
        setDefaultStatus()

        val phoneIntent = Intent(this, PhoneStateBroadcastReceiver::class.java)
        sendBroadcast(phoneIntent)

        val codeIntent = Intent(this, SecretCode::class.java)
        sendBroadcast(codeIntent)

        handler = Handler(Looper.getMainLooper())
        handler!!.post(runTask)

        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter().apply {
                addAction("titlesReceiver")
                addAction("removeReceiver")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(fytReceiver, intentFilter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(fytReceiver, intentFilter)
                }
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.e("NotificationListener", "Error registering receiver: ${e.message}")
            }
        }
    }  

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeWindowView()
        setDefaultStatus()
    }

    private fun setDefaultStatus() {
        mediaController?.let {
            it.registerCallback(callback)
            meta = it.metadata
            try {
                mState = it.playbackState?.state
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
            // update widget if the music is already playing
            if (meta != null && mState == PlaybackState.STATE_PLAYING) {
                Handler(Looper.getMainLooper()).postDelayed({
                    activeControllerPackage = (mediaController?.getPackageName()).toString()
                    shouldExclude = containsExcludedMediaPackage(activeControllerPackage)
                    setStatus(2)
                }, 2000)
            }
            if (MusicService.state && MusicService.music_name != "" && MusicService.music_name != "Unknown") {
                Handler(Looper.getMainLooper()).postDelayed({
                    shouldExclude = containsExcludedMediaPackage("com.syu.music")
                    setStatus(1)
                }, 2000)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupResources()
    }

    @CallSuper
    override fun onDestroy() {  
        destroyed.set(true)
        cleanupResources()  
        super.onDestroy()
    }

    private fun cleanupResources() { 
        if (isCleanedUp) return
        isCleanedUp = true

        removeWindowView()

        safeUnregisterReceiver()
        safeUnregisterSessionListener()

        mediaController?.unregisterCallback(callback)
        mediaController = null

        handler?.removeCallbacksAndMessages(null)

        handler = null

        controllers?.clear()
        controllers = null
        contextRef.clear()

        meta = null
        musicState = null
        musicName = null
        authorName = null
        album = null
        song = null
        songCur = null
        artist = null

        windowManager = null
        mediaSessionManager = null
        settings = null
    }

    private fun safeUnregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(fytReceiver)
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w("NotificationListener", "Receiver already unregistered: ${e.message}")
            }
        }
    } 

    private fun safeUnregisterSessionListener() {
        if (isSessionListenerRegistered) {
            try {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
                isSessionListenerRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w("NotificationListener", "Session listener already removed or not registered: ${e.message}")
            } catch (e: Exception) {
                Log.e("NotificationListener", "Error removing session listener: ${e.message}")
            }
        }
    } 

    private val fytReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "titlesReceiver") {
                val retriever = MediaMetadataRetriever()
                val bundle: Bundle? = intent.extras!!
                fytState = bundle!!.getBoolean("play_state", false)
                path = bundle.getString("play_path") ?: ""
                fytCurMinutes = bundle.getLong("play_cur", 0L)
                val file = File(path!!)
                if (file.exists()) {
                    try {
                        FileInputStream(file).use { fis ->
                            retriever.setDataSource(fis.fd, 0, file.length())
                        }      
                    } catch (e: IllegalArgumentException) {
                        e.printStackTrace()
                        retriever.setDataSource(path!!)
                    } finally {
                        musicName = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        authorName = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        album = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).toString()
                        retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.let { fytTotalMinutes = it.toLong() }

                        if (musicNamePrev != musicName) {
                            musicNamePrev = musicName.toString()
                            prevCurFyt = 0
                        }

                        val filename = file.getName()
                        if (filename.isNotEmpty() && filename.contains(".")) {
                            pathName = filename.substring(0, filename.lastIndexOf("."))
                        }

                        if (currentState == PlaybackState.STATE_PLAYING) {
                            mediaController?.transportControls?.pause()
                        }

                        if (musicName != null && musicName!!.isNotEmpty() && musicName != "Unknown" && musicName != "null" && song != null && song != musicName && song != pathName!!) {
                            fytSet = false
                        } 
                        if(fytState && !fytSet && fytAllowed && musicName != null && musicName!!.isNotEmpty() && musicName != "Unknown" && musicName != "null") {    
                            fytSet = true
                            if (currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED) {
                                removeWindowView()
                            }
                            shouldExclude = containsExcludedMediaPackage("com.syu.music")
                            updateWidgetPlayState(context, true)
                            setStatus(1)
                        } 
                        if (!fytState && fytSet) {
                            if (currentState != PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED) {
                                removeWindowView()
                            }
                            fytSet = false
                            updateWidgetPlayState(context, false)
                        }  
                        retriever.release()                  
                    }
                }
            } else if (intent.action == "removeReceiver") {
                if (currentState != PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED) {
                    removeWindowView()
                }
                fytSet = false
                updateWidgetPlayState(context, false)
            }
        }
    }    

    private fun imContextSystem(context: Context): Boolean {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, 0)
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private val runTask = RunTaskRunnable(this)

    private class RunTaskRunnable(service: NotificationListener) : Runnable {
        private val serviceRef = WeakReference(service)
        override fun run() {
            val service = serviceRef.get() ?: return
            if (service.destroyed.get()) return
            val context = service.contextRef.get() ?: return
            val am = context.getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive && service.addedViews.isEmpty() && !PhoneListener.CALLING && !service.started) {
                // onActiveSessionsChanged switches between sources flawlessly as long as music continues to play,
                // it doesn't switch when user had paused previous music source before playing the new one
                service.checkActiveSessions()
            }
            if (am.isMusicActive && service.fytState && !PhoneListener.CALLING && !service.started) {
                // sometimes when fyt player is still active MediaController looses active session
                service.checkActiveSessions()
            }
            service.handler!!.postDelayed(this, 10)
        }
    }

    fun removeWindowView() {
        for (view in addedViews) {
            try {
                view?.let {
                    windowManager?.removeViewImmediate(it)
                }
            } catch (_: Exception) {}
        }
        displayedText = ""
        addedViews.clear()
        overlayView = null
        marqueeTextView = null
    }

    suspend fun removeWindowViewCorutine() {
        removeWindowView()
        clearAppCache(this)
    }

    fun clearAppCache(context: Context) {
        clearCacheDir(context.cacheDir)
        clearCacheDir(context.externalCacheDir)
    }

    private fun clearCacheDir(dir: File?) {
        dir?.let { 
            if (it.isDirectory) {
                it.listFiles()?.forEach { child ->
                    child.deleteRecursively()
                }
            }
        }
    }

    class StaticMediaControllerCallback(listenerRef: WeakReference<NotificationListener>) : MediaController.Callback() {
        private val serviceRef = listenerRef
        
        override fun onSessionDestroyed() {
            val service = serviceRef.get() ?: return
            val context = service.contextRef.get() ?: return
            if (!service.fytState) {
                service.removeWindowView()
                updateWidgetPlayState(context, false)
            }
            super.onSessionDestroyed()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val service = serviceRef.get() ?: return
            if (service.destroyed.get()) return
            super.onMetadataChanged(metadata)
            service.prevMinutes = 0
            Helpers.counter = 0
            service.meta = metadata
            set()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val service = serviceRef.get() ?: return
            val context = service.contextRef.get() ?: return
            if (service.destroyed.get()) return
            super.onPlaybackStateChanged(state)
            // 2 - PAUSED, 3 - PLAYING
            service.currentState = state?.state
            service.prevMinutes = 0
            if (service.currentState == PlaybackState.STATE_PAUSED
                || service.currentState == PlaybackState.STATE_STOPPED
                || service.currentState == PlaybackState.STATE_BUFFERING) {
                service.settings!!.edit {
                    putInt("prevState", service.currentState!!)
                }
                Helpers.counter = 0
                if (!service.fytState) {
                    service.removeWindowView()
                    updateWidgetPlayState(context, false)
                }
            } else if (service.currentState == PlaybackState.STATE_PLAYING) {
                set()
                updateWidgetPlayState(context, true)
            }
        }

        fun set() {
            val service = serviceRef.get() ?: return
            if (service.currentState == PlaybackState.STATE_PAUSED || service.currentState == PlaybackState.STATE_STOPPED) {  
                Helpers.counter = 0
                service.musicState = "false"
            } else if (service.currentState == PlaybackState.STATE_PLAYING) {
                // prevents youtube live to add view every ~second
                var dur = service.meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                // prevents flickering on adding view
                var songTest = service.meta?.getString(MediaMetadata.METADATA_KEY_TITLE) 
                if (songTest != null) {
                    if (songTest!!.isNotEmpty()) {
                        service.songCur = service.meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    }
                }
                if (!service.songCur.equals(service.settings!!.getString("songPrev", "prev")) 
                    || service.settings!!.getInt("prevState", PlaybackState.STATE_STOPPED) == PlaybackState.STATE_STOPPED 
                    || service.settings!!.getInt("prevState", PlaybackState.STATE_STOPPED) == PlaybackState.STATE_PAUSED
                    || service.settings!!.getInt("prevState", PlaybackState.STATE_STOPPED) == PlaybackState.STATE_BUFFERING) {
                    service.settings!!.edit {
                        putString("songPrev", service.songCur)
                        putInt("prevState", service.currentState!!)
                    }
                    service.removeWindowView()
                    activeControllerPackage = (service.mediaController?.getPackageName()).toString()
                    service.shouldExclude = service.containsExcludedMediaPackage(activeControllerPackage)
                    if (dur != 0.toLong() && !service.started) { // not live
                        service.musicState = "true"
                        service.setStatus(2) 
                    } else { // live
                        if (Helpers.counter == service.count && !service.started) {
                            Helpers.counter++
                            service.musicState = "true"
                            service.curMinutes = 0
                            service.setStatus(2)                 
                        }
                    }
                }
            }
        }
    }

    val callback = StaticMediaControllerCallback(WeakReference(this))

    fun setStatus(mediaSource: Int) = runBlocking {
        val job1 = launch { removeWindowViewCorutine() }
        val job2 = launch { setStatusCorutine(mediaSource) }

        job1.join()
        job2.join()
    }

suspend fun setStatusCorutine(mediaSource: Int) {
    started = true
    if (mediaSource == 2) {
        fytState = false
        fytSet = true
    }
    if (overlayView?.parent == null && addedViews.isEmpty()) {
        displayTitles = settings!!.getBoolean("titles_box", true)
        val excludeWidget = settings!!.getBoolean("exclude_box", true)
        var numUp = 0
        var ttfHeight = 0.0f
        if (displayTitles && !shouldExclude) {
            var marginString = "margin_portrait"
            var widthString = "width_portrait"
            val configuration = resources.configuration
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                marginString = "margin_landscape"
                widthString = "width_landscape"
            }
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val marginPercentage = settings!!.getInt("marginPercentage", (screenWidth * 0.1275).toInt())
            val widthPercentage = settings!!.getInt("widthPercentage", (screenWidth * 0.45).toInt())
            marginLeft = settings!!.getInt(marginString, marginPercentage)
            width = settings!!.getInt(widthString, widthPercentage)
            up = settings!!.getInt("up", 0)
            down = settings!!.getInt("down", 0)
            size = settings!!.getInt("size", 16)
            typefaceInt = settings!!.getInt("typeface", 0)
            settings!!.getString("color", "#FFFFFF")?.let { color -> statusColor = color }
            settings!!.getString("bg_color", "transparent")?.let { color -> statusBgColor = color }
            fytData = settings!!.getInt("fytData", 1)
            ttfUp = (settings!!.getInt("ttf_up", 0)).toFloat()
            ttfDown = (settings!!.getInt("ttf_down", 0)).toFloat()
            displayArtist = settings!!.getBoolean("artist_box", true)

            numUp = when {
                down > 0 -> abs(down)
                up > 0 -> -abs(up)
                else -> 0
            }

            ttfHeight = when {
                ttfDown > 0.0f -> abs(ttfDown).toFloat()
                ttfUp > 0.0f -> -abs(ttfUp).toFloat()
                else -> 0.0f
            }
        }

        try {
            if (displayTitles && !shouldExclude) {
                val height = if (typefaceInt == 3) {
                    statusBarHeight + (size * 2.5f)
                } else {
                    if (size > 22) {
                        statusBarHeight + size
                    } else statusBarHeight
                }
                
                val bgIsTransparent = (returnColor(statusBgColor) == Color.TRANSPARENT)
                val pixelFormat = if (bgIsTransparent) PixelFormat.RGBA_8888 else PixelFormat.OPAQUE

                parameters = WindowManager.LayoutParams(
                    width,
                    height.toInt(),
                    overlayParam,
                    flagParam or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    pixelFormat
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = marginLeft
                    y = numUp
                }
            }

            if (fytState && fytAllowed && (mediaSource == 0 || mediaSource == 1)) {
                if (fytData == 1) {
                    song = musicName
                    artist = authorName
                    if (artist?.isEmpty() == true || artist == "Unknown") {
                        artist = album
                    }
                } else if (fytData == 2) {
                    val file = File(path!!)
                    val filename = file.getName()
                    song = filename.substring(0, filename.lastIndexOf("."))
                    artist = null
                }
                source = "fyt"
                settings!!.edit {
                    putString("lastMediaController", "com.syu.music")
                }
                val lrc = Lrc()
                val info = lrc.getId3Info(path)
                val dataPic = info.dataPic
                val bp: Bitmap = if (dataPic != null && dataPic.isNotEmpty()) {
                    var b = BitmapFactory.decodeByteArray(dataPic, 0, dataPic.size)
                    if (b != null) b = getRoundedCornerBitmap(b)
                    b
                } else {
                    val drawable: Drawable = ContextCompat.getDrawable(applicationContext, R.drawable.music_album_def)!!
                    drawableToBitmap(drawable)
                }
                albumCover = bp
                totalMinutes = fytTotalMinutes
                curMinutes = fytCurMinutes
            }

            if (!fytState && (mediaSource == 0 || mediaSource == 2)) {
                fytAllowed = false
                Handler(Looper.getMainLooper()).postDelayed({
                    fytAllowed = true
                }, 2500)
                song = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                if (artist == null || artist?.isEmpty() == true) artist = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                if (artist == null || artist?.isEmpty() == true) artist = meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                if (artist == null || artist?.isEmpty() == true) artist = meta?.getString(MediaMetadata.METADATA_KEY_WRITER)
                if (artist == null || artist?.isEmpty() == true) artist = meta?.getString(MediaMetadata.METADATA_KEY_COMPOSER)

                activeControllerPackage = (mediaController?.getPackageName()).toString()
                settings!!.edit {
                    putString("lastMediaController", activeControllerPackage)
                }
                source = "mediaController"
                musicState = "true"
                var bitmap: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (bitmap == null) bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                if (bitmap == null) bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                if (bitmap == null) {
                    val ctx = contextRef.get()
                    if (ctx != null) {
                        bitmap = getHighResContextIcon(ctx, activeControllerPackage)
                    }
                }
                albumCover = bitmap!!

                controllerTotalMinutes = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)!!
                mediaController?.playbackState?.let { state ->
                    controllerCurMinutes = if (controllerTotalMinutes == 0L) 0L else state.position
                } ?: run { controllerCurMinutes = 0 }
                totalMinutes = controllerTotalMinutes
                curMinutes = controllerCurMinutes
            }

            if (displayTitles && !shouldExclude) {
                if (artist != null && activeControllerPackage != "app.revanced.android.youtube" && activeControllerPackage != "com.google.android.youtube" && displayArtist) {
                    if (artist!!.isNotEmpty()) {
                        displayedText = if (!song!!.contains(artist!!) && artist != "Unknown") {
                            getString(R.string.artist_and_song_str, "$artist", "$song") + getString(R.string.space)
                        } else {
                            getString(R.string.song_str, "$song") + getString(R.string.space)
                        }
                    }
                } else {
                    displayedText = getString(R.string.song_str, "$song") + getString(R.string.space)
                }

                val drawView = MarqueeDrawView(applicationContext).apply {
                    setText(displayedText)

                    // text color
                    try {
                        setTextColor(statusColor.toColorInt())
                    } catch (e: Exception) {
                        setTextColor(Color.WHITE)
                    }

                    // size
                    setTextSizeSp(size.toFloat())

                    val bg = returnColor(statusBgColor)
                    // corner radius (optional); 0 for square
                    val cornerRadiusPx = 0f
                    setBgColorInt(bg, cornerRadiusPx)

                    // typeface handling
                    if (typefaceInt == 3) {
                        val filePath = settings!!.getString("typeface_ttf", "empty")
                        val file = File(filePath!!)
                        if (file.exists()) {
                            setTypefaceFile(file)
                            // vertical offset for ttf
                            translationY = ttfHeight
                        } else {
                            typefaceInt = 0
                            settings!!.edit {
                                putInt("typeface", 0)
                            }
                            setTypefaceFile(null)
                            setTypefaceMode(0)
                        }
                    } else {
                        // built-in styles
                        setTypefaceFile(null)
                        setTypefaceMode(typefaceInt)
                    }

                    // marquee
                    enableScroll(true)
                }

                ViewCompat.setBackground(drawView, null)

                // ensure view has full alpha and appropriate layer type
                drawView.alpha = 1f
                drawView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

                if (displayTitles && !shouldExclude && drawView.parent == null) {
                    windowManager!!.addView(drawView, parameters)
                    addedViews.add(drawView)
                    overlayView = drawView
                }
            }

            // WIDGET update
            excludeForWidget = shouldExcludeWidget(excludeWidget, shouldExclude)
            if (!excludeForWidget && (song != lastWidgetSong || artist != lastWidgetArtist)) {
                lastWidgetSong = song
                lastWidgetArtist = artist
                if (artist == null) artist = ""
                updateWidgetFromService(this, song.toString(), artist.toString(), albumCover)
            }

            paused = false
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }
    started = false
}

    private fun containsExcludedMediaPackage(activePackage: String): Boolean {
        val statsPrefs = getSharedPreferences("ExcludeAppsPrefs", MODE_PRIVATE)
        val apps = statsPrefs.getStringSet("exclude_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (apps.contains(activePackage)) return true else return false
    }

    private fun shouldExcludeWidget(excludeWidget: Boolean, shouldExcludeApps: Boolean): Boolean {
        if (excludeWidget && shouldExcludeApps) {
            return true 
        } else return false
    }

    private fun returnColor(colorString: String): Int {
        return if (colorString == "transparent") {
            Color.TRANSPARENT
        } else colorString.toColorInt()
    }

    fun checkActiveSessions() {
        val ctrlrs: MutableList<MediaController>? = mediaSessionManager?.getActiveSessions(componentName)
        sessionListener.onActiveSessionsChanged(ctrlrs)
    }

    private fun pickController(controllers: MutableList<MediaController>?): MediaController? {
        if (controllers == null) return null
        for (mc in controllers) {
            if (mc.playbackState != null && mc.playbackState?.state == PlaybackState.STATE_PLAYING) {
                return mc
            }
        }
        return if (controllers.isNotEmpty()) controllers[0] else null
    }

    @Throws(PackageManager.NameNotFoundException::class)
    fun getHighResContextIcon(context: Context, packageName: String): Bitmap {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        var iconDrawable: Drawable? = null

        if (appInfo.icon != 0) {
            try {
                val resources = pm.getResourcesForApplication(appInfo)
                val densities = intArrayOf(640, 480, 320, 240, 160)  // From XXXHIGH to MEDIUM

                for (density in densities) {
                    iconDrawable = try {
                        resources.getDrawableForDensity(appInfo.icon, density, context.theme)
                    } catch (e: Resources.NotFoundException) {
                        null
                    }
                    if (iconDrawable != null) break
                }
            } catch (e: Exception) {
                Log.e("NotificationListener", "Error getting high res icon: ${e.message}")
            }
        }

        // Fallback to default icon if there's no hugh res icon
        iconDrawable = iconDrawable ?: pm.getApplicationIcon(packageName)

        return drawableToBitmap(iconDrawable)
    }
    
    fun drawableToBitmap(drawable: Drawable?): Bitmap {
        val bitmap = createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun getRoundedCornerBitmap(bitmap: Bitmap): Bitmap {
        return try {
            val width = bitmap.width
            val height = bitmap.height

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val paint = Paint().apply {
                isAntiAlias = true
            }

            val rect = Rect(0, 0, width, height)
            val rectF = RectF(rect)

            canvas.drawARGB(0, 0, 0, 0)
            canvas.setDrawFilter(PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

            paint.color = ViewCompat.MEASURED_STATE_MASK  // same as original
            canvas.drawRoundRect(rectF, 35f, 35f, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, rect, rect, paint)

            output
        } catch (e: Exception) {
            bitmap
        }
    }

    class SessionListener(service: NotificationListener) : MediaSessionManager.OnActiveSessionsChangedListener {
        private val serviceRef = WeakReference(service)

        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            val service = serviceRef.get() ?: return
            if (service.destroyed.get() || service.isCleanedUp) return
            if (!controllers.isNullOrEmpty()) {
                if (service.mediaController != null && controllers[0].sessionToken != service.mediaController?.sessionToken) {
                    // Detach current controller
                    service.mediaController?.unregisterCallback(service.callback)
                    service.mediaController = null
                    if (!service.fytState) {
                        service.removeWindowView()
                    }
                }

                if (service.mediaController == null) {
                    
                    // Attach new controller
                    if (!service.fytState) {
                        service.removeWindowView()
                    }
                    service.mediaController = service.pickController(controllers)
                    service.mediaController?.registerCallback(service.callback)
                    service.mediaController?.metadata?.let { service.callback.onMetadataChanged(it) }
                    service.mediaController?.playbackState?.let { service.callback.onPlaybackStateChanged(it) }
                }
            }
        }
    }
}