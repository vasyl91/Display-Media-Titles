package vasyl.titles.fyt

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import vasyl.titles.FytOrigin
import vasyl.titles.FytPlayerBus
import vasyl.titles.FytTrack

/**
 * The stock sources of the FYT 7870 as one source of FYT tracks for [FytPlayerBus]: com.syu.media's
 * music and internet radio ([SyuMediaClient]), BT audio and the radio ([MsToolkitClient]). Only
 * the source the [Stock7870Arbiter] shows reaches the bus, so the titles see one FYT source, as on
 * the 7862.
 *
 * Rules:
 * - Only what plays is shown: a source that is paused publishes "not playing" (the title goes), a
 *   source with nothing to show or whose service went away publishes a removal.
 * - No empty title ever replaces one on screen ([StockTracks]); a track without a title is the
 *   same as no track. While com.syu.media briefly reports no track (its placeholder), the last
 *   track of that player stays and only its play state follows.
 * - BT and the radio report title, artist and name in separate updates: they are published
 *   [DEBOUNCE_MS] after a burst of them, so an old artist is never shown with a new title.
 * - A track is published when it changed, and once a second while it plays: after another app had
 *   the screen, the titles take an FYT source back only from its repeated "playing".
 * - Everything runs on one thread of its own; the bus is fed on the main thread.
 * - [control] sends a widget button to the source whose track the widget shows; nothing is started
 *   without a press, and a source that is not running is not brought up.
 *
 * [start] and [stop] are called on the main thread; a new [start] after [stop] starts afresh.
 */
object Stock7870Source {

    private const val TAG = "DmtStock7870"
    private const val DEBOUNCE_MS = 300L
    private const val REPEAT_MS = 1000L

    @Volatile
    private var run: Run? = null

    /** Starts reading the 7870's stock sources; a no-op while it runs. Main thread. */
    fun start(context: Context) {
        if (run != null) return
        run = Run(context.applicationContext).also { it.start() }
    }

    /** Stops reading them and removes what they showed. Main thread. */
    fun stop() {
        run?.stop()
        run = null
    }

    /**
     * Sends a widget button to the source of [origin]. [onResult] says whether it reached the
     * source; false when it does not run or cannot be reached (the caller opens its page then).
     * Any thread; [onResult] may come on another one.
     */
    fun control(origin: FytOrigin, action: StockAction, onResult: (Boolean) -> Unit) {
        val current = run
        if (current == null) {
            onResult(false)
            return
        }
        current.control(origin, action, onResult)
    }

    private class Run(private val app: Context) {

        private val thread = HandlerThread("FytStock").apply { start() }
        private val worker = Handler(thread.looper)
        private val main = Handler(Looper.getMainLooper())

        private val players = PlayerListener()
        private val music = SyuMediaClient(app, StockKind.MEDIA_MUSIC, SyuMediaProtocol.ACTION_MUSIC, worker, players)
        private val online = SyuMediaClient(app, StockKind.MEDIA_ONLINE, SyuMediaProtocol.ACTION_ONLINE, worker, players)
        private val ms = MsToolkitClient(app, worker, MsListener())

        // worker thread only
        private val arbiter = Stock7870Arbiter()
        private val lastRead = HashMap<StockKind, SyuMediaRead>()
        private val lastSong = HashMap<StockKind, SyuMediaSong>()
        private var radio = RadioState()
        private var bt = BtState()
        private var published: FytTrack? = null
        private var publishedAtMs = 0L
        private var overlayPending = false
        private val overlayPublish = Runnable { overlayPending = false; evaluate() }
        private val repeat = Runnable { evaluate() }

        fun start() {
            Log.i(TAG, "reading the FYT 7870's stock sources")
            music.start()
            online.start()
            ms.start()
        }

        fun stop() {
            music.stop()
            online.stop()
            ms.stop()
            worker.post {
                worker.removeCallbacks(repeat)
                worker.removeCallbacks(overlayPublish)
                if (published != null) {
                    published = null
                    main.post { FytPlayerBus.publishRemove(FytOrigin.STOCK_7870) }
                }
            }
            thread.quitSafely()
        }

        fun control(origin: FytOrigin, action: StockAction, onResult: (Boolean) -> Unit) {
            when (origin) {
                FytOrigin.MEDIA_MUSIC -> music.control(action, onResult)
                FytOrigin.MEDIA_ONLINE -> online.control(action, onResult)
                else -> onResult(false)
            }
        }

        private fun client(kind: StockKind): SyuMediaClient? = when (kind) {
            StockKind.MEDIA_MUSIC -> music
            StockKind.MEDIA_ONLINE -> online
            else -> null
        }

        /** Works out the track of the source shown and publishes it if needed. Worker thread. */
        private fun evaluate() {
            val now = SystemClock.elapsedRealtime()
            val track = when (val kind = arbiter.shown) {
                StockKind.MEDIA_MUSIC, StockKind.MEDIA_ONLINE -> lastRead[kind]?.let { read ->
                    lastSong[kind]?.let { StockTracks.player(kind, it, read.playing, read.progressMs, read.sampledAtMs) }
                }
                StockKind.MEDIA_BT -> StockTracks.bt(bt, arbiter.channel, now)
                StockKind.RADIO -> StockTracks.radio(radio, arbiter.channel, now)
            }
            worker.removeCallbacks(repeat)
            if (track == null) {
                if (published != null) {
                    published = null
                    Log.d(TAG, "${arbiter.shown}: nothing to show")
                    main.post { FytPlayerBus.publishRemove(FytOrigin.STOCK_7870) }
                }
                return
            }
            val previous = published
            val changed = previous == null || previous.origin != track.origin || !previous.sameState(track)
            if (changed || (track.playing && now - publishedAtMs >= REPEAT_MS)) {
                // A repeat is sent with the time it is sent, at least REPEAT_MS after the one
                // before, so FytPlayerBus does not take it for a duplicate.
                val toSend = if (changed) track else track.copy(receivedAt = now)
                if (changed) Log.d(TAG, "${arbiter.shown}: ${if (track.playing) "playing" else "paused"} '${track.title}'")
                published = toSend
                publishedAtMs = now
                main.post { FytPlayerBus.publish(toSend) }
            }
            if (track.playing) worker.postDelayed(repeat, REPEAT_MS)
        }

        private fun publishOverlaySoon() {
            if (overlayPending) return
            overlayPending = true
            worker.postDelayed(overlayPublish, DEBOUNCE_MS)
        }

        private inner class PlayerListener : SyuMediaClient.Listener {
            override fun onRead(read: SyuMediaRead) {
                val song = read.song?.takeUnless { it.isPlaceholder }
                if (song != null) lastSong[read.kind] = song
                lastRead[read.kind] = read
                if (arbiter.onPlayerRead(read.kind, read.playing, read.sampledAtMs)) evaluate()
            }

            override fun onGone(kind: StockKind) {
                lastRead.remove(kind)
                lastSong.remove(kind)
                if (arbiter.onPlayerGone(kind)) evaluate()
            }
        }

        private inner class MsListener : MsToolkitClient.Listener {
            override fun onUpdate(module: Int, code: Int, ints: IntArray?, strs: Array<String?>?) {
                val int = ints?.firstOrNull()
                val str = strs?.firstOrNull().orEmpty()
                when (module) {
                    MsProtocol.MODULE_MAIN -> if (code == MsProtocol.MAIN_APP_ID && int != null) onChannel(int)
                    MsProtocol.MODULE_RADIO -> {
                        when (code) {
                            MsProtocol.RADIO_FREQ -> if (int != null) radio = radio.withFreq(int)
                            MsProtocol.RADIO_PS -> radio = radio.withPs(str)
                        }
                        if (arbiter.shown == StockKind.RADIO) publishOverlaySoon()
                    }
                    MsProtocol.MODULE_BT -> {
                        bt = when (code) {
                            MsProtocol.BT_TITLE -> bt.copy(title = str)
                            MsProtocol.BT_ARTIST -> bt.copy(artist = str)
                            MsProtocol.BT_ALBUM -> bt.copy(album = str)
                            MsProtocol.BT_PLAY_STATE -> bt.copy(phonePlaying = int == 1)
                            else -> bt
                        }
                        if (arbiter.shown == StockKind.MEDIA_BT) publishOverlaySoon()
                    }
                }
            }

            private fun onChannel(channel: Int) {
                if (arbiter.onChannel(channel, SystemClock.elapsedRealtime())) {
                    val shown = arbiter.shown
                    Log.d(TAG, "channel $channel: $shown is shown")
                    when (shown) {
                        StockKind.RADIO -> ms.registerAgain(MsProtocol.MODULE_RADIO)
                        StockKind.MEDIA_BT -> ms.registerAgain(MsProtocol.MODULE_BT)
                        else -> client(shown)?.requestRefresh() // its last read may be old
                    }
                }
                // BT and the radio play only on their channel: what is shown may have stopped.
                evaluate()
            }

            override fun onGone() {
                arbiter.onChannelLost()
                radio = RadioState()
                bt = BtState()
                if (arbiter.shown.overlay) evaluate()
            }
        }
    }
}
