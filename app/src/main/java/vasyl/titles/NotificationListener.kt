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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

class NotificationListener : NotificationListenerService() {
    
    private lateinit var context: Context
    private lateinit var handler: Handler
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var settings: SharedPreferences
    private lateinit var windowManager: WindowManager
    
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
    private var displayArtist: Boolean = true
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
    private var componentName = ComponentName("vasyl.titles", "vasyl.titles.NotificationListener")

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

    override fun onCreate() {  
        super.onCreate()   
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {  
        super.onStartCommand(intent, flags, startId)
        
        if (componentName == null) {
            componentName = ComponentName(this, this::class.java)
        }
        
        componentName?.let {  
            requestRebind(it)  
            toggleNotificationListenerService(it)  
        }  
        return START_REDELIVER_INTENT  
    }

    private fun toggleNotificationListenerService(componentName: ComponentName) {  
        val pm = packageManager  
        pm.setComponentEnabledSetting(
            componentName,  
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP   
        )  
        pm.setComponentEnabledSetting(
            componentName,  
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP  
        )  
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        removeWindowView()
        sessionListener.let {
            if (this::mediaSessionManager.isInitialized) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(it)
            }
        }
        if (this::handler.isInitialized) {
            handler.removeCallbacks(runTask)
        }
        removeWindowView()
        unregisterReceiver(fytReceiver)
        
        if (componentName == null) {  
            componentName = ComponentName(this, this::class.java)  
        }
        componentName?.let { requestRebind(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeWindowView()
        sessionListener.let {
            if (this::mediaSessionManager.isInitialized) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(it)
            }
        }
        if (this::handler.isInitialized) {
            handler.removeCallbacks(runTask)
        }
        unregisterReceiver(fytReceiver)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onListenerConnected() {    
        super.onListenerConnected()  
        this.context = this

        settings = getSharedPreferences("savedPrefs", 0)    
        displayUI = settings.getBoolean("UI", true)
        fytData = settings.getInt("fytData", 1)

        paused = false
        
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) statusBarHeight = resources.getDimensionPixelSize(resourceId)

        if (isAppSystem(this)) {
            overlayParam = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
            flagParam = WindowManager.LayoutParams.TYPE_WALLPAPER
        } else {
            overlayParam = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flagParam = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, componentName)
        controllers = mediaSessionManager.getActiveSessions(componentName)
        mediaController = pickController(controllers!!)
        mediaController?.let {
            it.registerCallback(callback)
            meta = it.metadata
            try {
                mState = it.getPlaybackState()?.getState()
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
            if (meta != null && mState == PlaybackState.STATE_PLAYING) {
                setStatus(0)
            }
        }

        val phoneIntent = Intent(this, PhoneStateBroadcastReceiver::class.java)
        sendBroadcast(phoneIntent)

        val codeIntent = Intent(this, SecretCode::class.java)
        sendBroadcast(codeIntent)

        handler = Handler(Looper.getMainLooper())
        handler.post(runTask)

        val intentFilter = IntentFilter("titlesReceiver")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fytReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(fytReceiver, intentFilter)
        }
    }  

    private val fytReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "titlesReceiver") {
                val retriever = MediaMetadataRetriever()
                val bundle: Bundle? = intent.extras!!
                fytState = bundle?.getBoolean("play_state")!!
                path = bundle.getString("play_path")!!
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

                        val filename = file.getName()
                        if (filename.isNotEmpty() && filename.contains(".")) {
                            pathName = filename.substring(0, filename.lastIndexOf("."))
                        }
                        if (musicName != null && musicName!!.isNotEmpty() && musicName != "Unknown" && musicName != "null" && song != null && song != musicName && song != pathName!!) {
                            fytSet = false
                        } 
                        if(fytState && !fytSet && fytAllowed && musicName != null && musicName!!.isNotEmpty() && musicName != "Unknown" && musicName != "null") {   
                            fytSet = true
                            if (currentState == PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED) {
                                removeWindowView()
                            }
                            setStatus(1)
                        } 
                        if (!fytState && fytSet) {
                            if (!fytState && (currentState != PlaybackState.STATE_PLAYING || currentState == PlaybackState.STATE_STOPPED)) {
                                removeWindowView()
                            }
                            fytSet = false
                        }  
                        retriever.release()                  
                    }
                }
            }
        }
    }    

    private fun isAppSystem(context: Context): Boolean {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, 0)
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private val runTask = object : Runnable {
        override fun run() {
            val am = context.getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive && addedViews.isEmpty() && !PhoneListener.CALLING && !started) {
                // onActiveSessionsChanged switches between sources flawlessly as long as music continues to play,
                // it doesn't switch when user had paused previous music source before playing the new one
                checkActiveSessions()
            }
            if (am.isMusicActive && fytState && !PhoneListener.CALLING && !started) {
                // sometimes when fyt player is still active MediaController looses active session
                checkActiveSessions()
            }
            handler.postDelayed(this, 10)
        }
    }

    fun removeWindowView() {
        for (overlayView in addedViews) {
            try {
                overlayView?.let {
                    windowManager.removeView(overlayView)
                }            
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
        overlayView = null
        displayedText = ""
        addedViews.clear()
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

    private var callback: MediaController.Callback = object : MediaController.Callback() {
        override fun onSessionDestroyed() {
            if (!fytState) {
                removeWindowView() 
            }
            super.onSessionDestroyed()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            meta = metadata
            Helpers.counter = 0
            set()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            // 2 - PAUSED, 3 - PLAYING
            currentState = state?.state 
            if (currentState == PlaybackState.STATE_PAUSED
                || currentState == PlaybackState.STATE_STOPPED) {
                val editor = settings.edit()
                editor.putInt("prevState", currentState!!)
                editor.apply()
                Helpers.counter = 0
                if (!fytState) {
                    removeWindowView()
                }
            } else {
                set()
            }
        }

        fun set() {
            if (currentState == PlaybackState.STATE_PAUSED || currentState == PlaybackState.STATE_STOPPED) {  
                Helpers.counter = 0
            } else if (currentState == 3) {
                // prevents youtube live to add view every ~second
                var dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                // prevents flickering on adding view
                var songTest = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) 
                if (songTest != null) {
                    if (songTest!!.isNotEmpty()) {
                        songCur = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    }
                }
                if (!songCur.equals(settings.getString("songPrev", "prev")) || settings.getInt("prevState", 1) == 2) {
                    val editor = settings.edit()
                    editor.putString("songPrev", songCur)
                    editor.putInt("prevState", currentState!!)
                    editor.apply()
                    removeWindowView()
                    if (dur != 0.toLong() && !started) { // not live
                        setStatus(2) 
                    } else { // live
                        if (Helpers.counter == count && !started) {
                            Helpers.counter++
                            setStatus(2)              
                        }
                    }
                }
            }
        }
    }

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
        if (overlayView?.getParent() == null && addedViews.isEmpty()) {
            val marginPercentage = settings.getInt("marginPercentage", 255)
            val widthPercentage = settings.getInt("widthPercentage", 900)
            marginLeft = settings.getInt("margin", marginPercentage)
            width = settings.getInt("width", widthPercentage)
            up = settings.getInt("up", 0)
            down = settings.getInt("down", 0)
            size = settings.getInt("size", 16)
            typefaceInt = settings.getInt("typeface", 0)
            settings.getString("color", "#FFFFFF")?.let { color -> statusColor = color }
            settings.getString("bg_color", "transparent")?.let { color -> statusBgColor = color }
            fytData = settings.getInt("fytData", 1)
            ttfUp = (settings.getInt("ttf_up", 0)).toFloat()
            ttfDown = (settings.getInt("ttf_down", 0)).toFloat()
            displayArtist = settings.getBoolean("artist_box", true)

            var numUp = 0
            if (down > 0) {
                numUp = abs(down)
            } else if (up > 0) {
                numUp = -abs(up)
            } else if (up == 0 && down == 0) {
                numUp = 0
            }    
            var ttfHeight = 0.0f
            if (ttfDown > 0.0f) {
                ttfHeight = (abs(ttfDown)).toFloat()
            } else if (ttfUp > 0.0f) {
                ttfHeight = (-abs(ttfUp)).toFloat()
            } else if (ttfUp == 0.0f && ttfDown == 0.0f) {
                ttfHeight = 0.0f
            }      
            try {
                // Status bar
                val height = if (typefaceInt == 3) {
                    statusBarHeight + (size * 2.5f)
                } else {
                    if (size > 22) {
                        statusBarHeight + size
                    } else statusBarHeight
                }
                val parameters = WindowManager.LayoutParams(
                    width,
                    height.toInt(),
                    overlayParam,
                    flagParam or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = marginLeft
                    y = numUp
                }
                
                val tv = TextView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setTextColor(Color.parseColor(statusColor))
                    textSize = (size).toFloat()                   
                    if (typefaceInt != 3) {
                        setTypeface(null, typefaceInt)
                    } else if (typefaceInt == 3) {
                        val filePath = settings.getString("typeface_ttf", "empty")
                        val file = File(filePath)
                        if (file.exists()) {
                            val typeface = Typeface.createFromFile(filePath)
                            setTypeface(typeface)
                            y = ttfHeight
                        } else {
                            typefaceInt = 0
                            val editor = settings.edit()
                            editor.putInt("typeface", 0)
                            editor.apply()
                        }
                    }
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    isSingleLine = true
                    isSelected = true

                }

                if (fytState && fytAllowed && (mediaSource == 0 || mediaSource == 1)) {
                    if (fytData == 1) { // from metadata
                        song = musicName
                        artist = authorName
                        if(artist?.isEmpty() == true || artist == "Unknown"){
                            artist = album
                        }       
                    } else if (fytData == 2) { // from file title
                        val file = File(path!!)
                        val filename = file.getName()
                        song = filename.substring(0, filename.lastIndexOf("."))
                        artist = null
                    }    
                } 

                if (!fytState && (mediaSource == 0 || mediaSource == 2))  {
                    fytAllowed = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        fytAllowed = true
                    }, 2500)
                    song = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) 
                    if(artist == null || artist?.isEmpty() == true){
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    }
                    if(artist == null || artist?.isEmpty() == true) {
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                    }
                    if(artist == null || artist?.isEmpty() == true) {
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_WRITER)
                    }
                    if(artist == null || artist?.isEmpty() == true) {
                        artist = meta?.getString(MediaMetadata.METADATA_KEY_COMPOSER)
                    }                   
                }   

                var activeControllerPackage = (mediaController?.getPackageName()).toString()
                if (artist != null && activeControllerPackage != "app.revanced.android.youtube" && activeControllerPackage != "com.google.android.youtube" && displayArtist == true) {
                    if (artist!!.isNotEmpty()) {
                        if (!song!!.contains(artist!!) && artist != "Unknown") {
                            displayedText = getString(R.string.artist_and_song_str, "$artist", "$song") + getString(R.string.space)
                        } else {
                            displayedText = getString(R.string.song_str, "$song") + getString(R.string.space)
                        }                       
                    }
                } else {
                    displayedText = getString(R.string.song_str, "$song") + getString(R.string.space)
                }

                val layoutInflater = LayoutInflater.from(this)
                overlayView = layoutInflater.inflate(R.layout.marquee_overlay, null)
                marqueeTextView = overlayView?.findViewById(R.id.marqueeTextView)
                marqueeTextView?.apply {
                    this.text = displayedText
                    setBackgroundColor(returnColor(statusBgColor))
                    gravity = Gravity.CENTER or Gravity.START
                    setTextColor(Color.parseColor(statusColor))
                    textSize = (size).toFloat()                   
                    if (typefaceInt != 3) {
                        setTypeface(null, typefaceInt)
                    } else if (typefaceInt == 3) {
                        val filePath = settings.getString("typeface_ttf", "empty")
                        val file = File(filePath)
                        if (file.exists()) {
                            val typeface = Typeface.createFromFile(filePath)
                            setTypeface(typeface)
                            y = ttfHeight
                        } else {
                            typefaceInt = 0
                            val editor = settings.edit()
                            editor.putInt("typeface", 0)
                            editor.apply()
                        }
                    }
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    isSelected = true
                }

                if (overlayView?.getParent() == null) {
                    windowManager.addView(overlayView, parameters)
                    addedViews.add(overlayView)
                }
                
                paused = false
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
        started = false
    }

    private fun returnColor(colorString: String): Int {
        return if (colorString == "transparent") {
            Color.TRANSPARENT
        } else Color.parseColor(colorString)
    }

    fun checkActiveSessions() {
        val ctrlrs: MutableList<MediaController> = mediaSessionManager.getActiveSessions(componentName)
        sessionListener.onActiveSessionsChanged(ctrlrs)
    }

    private fun pickController(controllers: MutableList<MediaController>?): MediaController? {
        for (mc in controllers!!) {
            if (mc.playbackState != null && mc.playbackState?.state == PlaybackState.STATE_PLAYING) {
                return mc
            }
        }
        return if (controllers.isNotEmpty()) controllers[0] else null
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            if (controllers!!.isNotEmpty()) {
                if (mediaController != null && controllers[0].sessionToken != mediaController?.sessionToken) {
                    // Detach current controller
                    mediaController?.unregisterCallback(callback)
                    mediaController = null
                    if (!fytState) {
                        removeWindowView()
                    }
                }

                if (mediaController == null) {
                    // Attach new controller
                    mediaController = pickController(controllers)
                    mediaController?.registerCallback(callback)
                    callback.onMetadataChanged(mediaController?.metadata)
                    mediaController?.playbackState?.let { callback.onPlaybackStateChanged(it) }
                }
            }
        }
}