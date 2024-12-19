package vasyl.titles

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.abs

@Suppress("DEPRECATION")
class NotificationListener : NotificationListenerService() {
    
    private lateinit var context: Context
    private lateinit var handler: Handler
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var settings: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var windowManagerSecond: WindowManager
    
    private var up: Int = 0
    private var down: Int = 0
    private var ttfUp: Float = 0.0f
    private var ttfDown: Float = 0.0f
    private var size: Int = 16
    private var width: Int = 900
    private var marginLeft: Int = 255
    private var typefaceInt: Int = 0
    private var overlayParam: Int = 0
    private var statusBarHeight: Int = 0
    private var song: String? = ""
    private var artist: String? = ""
    private var displayArtist: Boolean = true
    private var statusColor = "#FFFFFF"
    private var statusBgColor = "transparent"
    private var paused: Boolean = false
    private var statusRemoved: Boolean = false
    private var doubleView: Boolean = false
    private var controllers: MutableList<MediaController>? = null
    private var ll: LinearLayout? = null   
    private var llSecond: LinearLayout? = null
    private var mediaController: MediaController? = null
    private var meta: MediaMetadata? = null
    private var mState: Int? = 0
    private val componentName = ComponentName("vasyl.titles", "vasyl.titles.NotificationListener")

    private var fytState: Boolean = false
    private var fytSet: Boolean = false
    private var musicName: String? = ""
    private var authorName: String? = ""
    private var album: String? = ""
    private var path: String? = ""
    private var fytData: Int = 1
    private var fytAllowed: Boolean = true // FYT sometimes updates data with some delay. This Boolean exist to not to interrupt changed media source.   

    var displayUI: Boolean = true

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sessionListener.let {
            mediaSessionManager.removeOnActiveSessionsChangedListener(it)
        }
        handler.removeCallbacks(runTask)
        if (!fytState) {
            removeWindowView()
        }
        requestRebind(ComponentName(this, NotificationListenerService::class.java))
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        this.context = this

        settings = getSharedPreferences("savedPrefs", 0)    
        displayUI = settings.getBoolean("UI", true)

        statusRemoved = false
        paused = false
        
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) statusBarHeight = resources.getDimensionPixelSize(resourceId)

        overlayParam = if (isAppSystem(this)) {
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManagerSecond = getSystemService(WINDOW_SERVICE) as WindowManager
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

        handler = Handler()
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
                val bundle: Bundle? = intent.extras!!
                fytState = bundle?.getBoolean("play_state")!!
                authorName = bundle.getString("play_artist")!!
                album = bundle.getString("play_album")!!                
                path = bundle.getString("play_path")!!
                musicName = bundle.getString("title")!!
                val file = File(path!!)
                val filename = file.getName()
                val pathName = filename.substring(0, filename.lastIndexOf("."))
                if (musicName!!.isNotEmpty() && musicName != "Unknown" && song != musicName && song != pathName) {
                    fytSet = false
                } 
                if(fytState && !fytSet && fytAllowed  && musicName!!.isNotEmpty() && musicName != "Unknown" && musicName != "null") {   
                    fytSet = true
                    setStatus(1)             
                }
                if (!fytState && fytSet) {
                    val am = context.getSystemService(AUDIO_SERVICE) as AudioManager
                    if (!am.isMusicActive) {
                        removeWindowView()                        
                    }
                    fytSet = false
                }
            }
        }
    }  

    private fun isAppSystem(context: Context): Boolean {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, 0)
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    override fun onDestroy() {
        sessionListener.let {
            mediaSessionManager.removeOnActiveSessionsChangedListener(it)
        }
        handler.removeCallbacks(runTask)
        removeWindowView()
        unregisterReceiver(fytReceiver)
    }

    private val runTask = object : Runnable {
        override fun run() {
            val am = context.getSystemService(AUDIO_SERVICE) as AudioManager
            if (!am.isMusicActive || PhoneListener.CALLING) {
                if (!fytState) {
                    removeView()
                }  
            }
            if (am.isMusicActive && ll == null && !PhoneListener.CALLING) {
                // onActiveSessionsChanged switches between sources flawlessly as long as music continues to play,
                // it doesn't switch when user had paused previous music source before playing the new one
                checkActiveSessions()
            }  
            if (am.isMusicActive && fytState && !PhoneListener.CALLING) {
                // sometimes when fyt player is still active MediaController looses active session
                checkActiveSessions()
            }
            handler.postDelayed(this, 10)
        }
    }

    fun removeView() {
        if (!statusRemoved) {
            statusRemoved = true // prevents running more than once in runTask
            removeWindowView()
        }
    }

    fun removeWindowView() {
        ll?.let {
            windowManager.removeViewImmediate(it)
            ll = null
        }
        if (doubleView) {
            llSecond?.let {
                windowManagerSecond.removeViewImmediate(it)
                llSecond = null
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

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            // 2 - PAUSED, 3 - PLAYING
            val currentState = state?.state
            if (currentState == 2 && !paused) {
                paused = true
                if (!fytState) {
                    removeWindowView()
                }
            } else if (currentState == 3) {
                // prevents youtube live to add the view every ~second
                var dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (dur != 0.toLong() || paused) {
                    setStatus(2)
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            if (meta == null) {
                meta = metadata
                if (!fytState) {
                    setStatus(2)   
                }
            } else if (metadata != null && meta != null) {
                // Check if the new title is different from the previous one
                if (metadata.getString(MediaMetadata.METADATA_KEY_TITLE) != meta!!.getString(MediaMetadata.METADATA_KEY_TITLE)) {
                    meta = metadata
                    if (!fytState) {
                        setStatus(2)   
                    }
                }
            }
        }
    }

    fun setStatus(mediaSource: Int) {
        removeWindowView()
        if (mediaSource == 2) {
            fytState = false
            fytSet = true
        }
        if (ll?.windowToken == null) {
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
            doubleView = settings.getBoolean("double_view", false)

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
                    WindowManager.LayoutParams.TYPE_WALLPAPER or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = marginLeft
                    y = numUp
                }

                ll = LinearLayout(this).apply {
                    setBackgroundColor(returnColor(statusBgColor))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                if (doubleView) {
                    llSecond = LinearLayout(this).apply {
                        setBackgroundColor(returnColor(statusBgColor))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }
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

                lateinit var tvSecond: TextView
                if (doubleView) {
                    tvSecond = TextView(this).apply {
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
                    }, 2000)
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

                if (artist != null && displayArtist == true) {
                    if (artist!!.isNotEmpty()) {
                        if (!song!!.contains(artist!!) && artist != "Unknown") {
                            tv.text = getString(R.string.artist_and_song_str, "$artist", "$song") + getString(R.string.space)
                            if (doubleView) {
                                tvSecond.text = getString(R.string.artist_and_song_str, "$artist", "$song") + getString(R.string.space)
                            }
                        } else {
                            tv.text = getString(R.string.song_str, "$song") + getString(R.string.space)
                            if (doubleView) {
                                tvSecond.text = getString(R.string.song_str, "$song") + getString(R.string.space)
                            }
                        }                       
                    }
                } else {
                    tv.text = getString(R.string.song_str, "$song") + getString(R.string.space)
                    if (doubleView) {
                        tvSecond.text = getString(R.string.song_str, "$song") + getString(R.string.space)
                    }
                }
                ll?.addView(tv)
                windowManager.addView(ll, parameters)
                if (doubleView) {
                    llSecond?.addView(tvSecond)
                    windowManagerSecond.addView(llSecond, parameters)
                }
                statusRemoved = false
                paused = false
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
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