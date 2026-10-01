package vasyl.titles

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vasyl.titles.excludeapps.AppScope
import vasyl.titles.widget.isMusicPlaying

/**
 * Receives the "launcher music" updates of the FYT music player (com.syu.music).
 *
 * Every update is handled synchronously. The service stays alive while the player keeps sending
 * updates (about one per second while it plays) and stops itself after [IDLE_STOP_MS] of silence;
 * stopping after every update re-created the service once per second.
 * The data is forwarded in-process through [FytPlayerBus] instead of an implicit broadcast.
 */
class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"
        private const val REMOVE_CONFIRM_DELAY_MS = 500L
        private const val IDLE_STOP_MS = 30_000L

        const val MUSICSERVICE = "com.fyt.launcher.music"
        const val MUSIC_PKG = "com.syu.music"
        const val NEXTMUSIC = "com.syu.music.next"
        const val PLAYPAUSEMUSIC = "com.syu.music.playpause"
        const val PLAY_ALBUM = "play_album"
        const val PLAY_ARTIST = "play_artist"
        const val PLAY_CURMINUTES = "play_cur"
        const val PLAY_PATH = "play_path"
        const val PLAY_STATE = "play_state"
        const val PLAY_TOTALMINUTES = "play_total"
        const val PREVMUSIC = "com.syu.music.prev"
        const val REMOVE_MUSIC = "com.fyt.systemui.remove"
        const val TITLE = "title"
        const val TITLES_RECEIVER = "titlesReceiver"
        const val REMOVE_RECEIVER = "removeReceiver"
        const val PLAY_SOURCE = "source"
        const val SOURCE = "fyt"

        // Last state reported by the player. Written on the main thread, read from widget callbacks
        // (Glance worker threads), hence @Volatile.
        @Volatile @JvmStatic var album_cover: ByteArray? = null
        @Volatile @JvmStatic var music_name: String = ""
        @Volatile @JvmStatic var author_name: String = ""
        @Volatile @JvmStatic var music_path: String = ""
        @Volatile @JvmStatic var state: Boolean = false
        @Volatile @JvmStatic var album: String = ""
        @Volatile @JvmStatic var TOTALMINUTES: Long = 0
        @Volatile @JvmStatic var CURMINUTES: Long = 0

        /** Pending "player stopped" confirmation; only touched on the main thread. */
        private var pendingRemove: Job? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MUSICSERVICE) {
            try {
                handlePlayerUpdate(intent)
            } catch (e: RuntimeException) {
                // Exported service: never let malformed extras (e.g. BadParcelableException) crash us.
                Log.w(TAG, "Ignoring malformed player update", e)
            }
        }
        // Not START_STICKY forever (as before) and not stopped after every update either.
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleStop)
        super.onDestroy()
    }

    private fun handlePlayerUpdate(intent: Intent) {
        val extras: Bundle = intent.extras ?: Bundle.EMPTY

        TOTALMINUTES = extras.getLong(PLAY_TOTALMINUTES, 0L)
        CURMINUTES = extras.getLong(PLAY_CURMINUTES, 0L)
        music_name = extras.getString(TITLE).orEmpty()
        author_name = extras.getString(PLAY_ARTIST).orEmpty()
        music_path = extras.getString(PLAY_PATH).orEmpty()
        state = extras.getBoolean(PLAY_STATE, false)
        album = extras.getString(PLAY_ALBUM).orEmpty()

        if (!music_name.contains("Unknown")) {
            cancelPendingRemove()
            FytPlayerBus.publish(
                FytTrack(
                    playing = state,
                    title = music_name,
                    artist = author_name,
                    album = album,
                    path = music_path,
                    durationMs = TOTALMINUTES,
                    positionMs = CURMINUTES,
                )
            )
        } else {
            scheduleRemoveIfStillStopped(applicationContext)
        }
    }

    private fun cancelPendingRemove() {
        pendingRemove?.cancel()
        pendingRemove = null
    }

    private fun scheduleRemoveIfStillStopped(context: Context) {
        if (state) return
        cancelPendingRemove()
        pendingRemove = AppScope.launch(Dispatchers.Main) {
            if (!isMusicPlaying(context)) return@launch
            // The first state sent by the player after it starts is "false": confirm it before removing.
            delay(REMOVE_CONFIRM_DELAY_MS)
            if (!state) FytPlayerBus.publishRemove()
        }
    }
}
