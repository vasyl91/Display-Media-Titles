package vasyl.titles.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log

/** One read of a com.syu.media player: its track (null: none), play state and position. */
class SyuMediaRead(
    val kind: StockKind,
    val song: SyuMediaSong?,
    val playing: Boolean,
    val progressMs: Long,
    val sampledAtMs: Long,
)

/** [ParcelReader] over a real reply parcel. */
private class ParcelAdapter(private val parcel: Parcel) : ParcelReader {
    override fun readInt(): Int = parcel.readInt()
    override fun readString(): String? = parcel.readString()
    override fun dataAvail(): Int = parcel.dataAvail()
}

/**
 * The binding to one of com.syu.media's two playback services, read only (see [SyuMediaProtocol];
 * a port of the FYT Launcher Mod's com.fyt.car.SyuMediaClient without its controls and favorites).
 *
 * Rules:
 * - The service is bound without BIND_AUTO_CREATE: this app only listens and never brings the
 *   player up. The connection comes as soon as the service runs, and again after each restart.
 *   The internet radio service lives only while a page of com.syu.media binds it, so it comes and
 *   goes.
 * - Every binder call goes out on [worker], never on the main thread. A callback from the player
 *   only asks for a read: [refresh] reads track, state and position together, so they always
 *   belong to each other. While the player plays it is read once a second, which also makes up
 *   for a callback that got lost.
 * - When the service goes away (disconnected, binding died) [Listener.onGone] says so: the caller
 *   removes what was shown, as nothing plays any more. A binding that died is made again after
 *   [REBIND_MS].
 * - A read whose play state could not be had is no read: "not playing" would take the title off
 *   a track that plays. It is tried again a second later if the player last played.
 * - After [stop] nothing is reported any more, late callbacks of the player included.
 */
internal class SyuMediaClient(
    private val app: Context,
    val kind: StockKind,
    private val action: String,
    private val worker: Handler,
    private val listener: Listener,
) : ServiceConnection {

    /** Called on the worker thread. */
    interface Listener {
        fun onRead(read: SyuMediaRead)
        fun onGone(kind: StockKind)
    }

    private val callback = CallbackStub()
    private val tick = Runnable { refresh() }

    @Volatile
    private var stopped = false

    // worker thread only
    private var player: IBinder? = null
    private var lastPlaying = false

    /** Binds the service; any thread. */
    fun start() {
        worker.post { bind() }
    }

    /**
     * Unbinds and unregisters the callback. Main thread. The worker may be quit right after this:
     * the unregistering is posted before that.
     */
    fun stop() {
        stopped = true
        unbindQuietly()
        worker.post {
            worker.removeCallbacks(tick)
            val p = player ?: return@post
            player = null
            call(p, SyuMediaProtocol.TX_UNREGISTER_CALLBACK, callback)?.recycle()
        }
    }

    /** Reads the player again, e.g. when it became the source shown. Any thread. */
    fun requestRefresh() {
        if (!stopped) worker.post { refresh() }
    }

    private fun bind() {
        if (stopped) return
        val bound = try {
            app.bindService(Intent(action).setPackage(SyuMediaProtocol.PACKAGE), this, 0)
        } catch (e: RuntimeException) { // SecurityException
            Log.w(TAG, "$kind: bindService failed", e)
            false
        }
        if (bound) {
            Log.d(TAG, "$kind: bound $action")
        } else {
            Log.i(TAG, "$kind: ${SyuMediaProtocol.PACKAGE} has no $action")
            unbindQuietly()
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        worker.post {
            if (stopped || service == null) return@post
            player = service
            Log.d(TAG, "$kind: connected")
            call(service, SyuMediaProtocol.TX_REGISTER_CALLBACK, callback)?.recycle()
            refresh()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        // The service stopped or its process died; the binding stays and connects again later.
        Log.d(TAG, "$kind: disconnected")
        worker.post { dropPlayer() }
    }

    override fun onBindingDied(name: ComponentName?) {
        // com.syu.media was updated or reinstalled: this binding is over, a new one is needed.
        Log.d(TAG, "$kind: binding died, binding again in $REBIND_MS ms")
        worker.post {
            dropPlayer()
            unbindQuietly()
            if (!stopped) worker.postDelayed({ bind() }, REBIND_MS)
        }
    }

    private fun dropPlayer() {
        worker.removeCallbacks(tick)
        if (player == null) return
        player = null
        lastPlaying = false
        if (!stopped) listener.onGone(kind)
    }

    private fun refresh() {
        worker.removeCallbacks(tick)
        val p = player ?: return
        if (stopped) return
        val sampledAt = SystemClock.elapsedRealtime()
        val song = call(p, SyuMediaProtocol.TX_GET_PLAYING_SONG, null)?.let { reply ->
            try {
                SyuMediaSong.read(ParcelAdapter(reply))
            } catch (e: RuntimeException) {
                Log.w(TAG, "$kind: unreadable song", e)
                null
            } finally {
                reply.recycle()
            }
        }
        val playingState = readInt(p, SyuMediaProtocol.TX_IS_PLAYING)
        val progress = readInt(p, SyuMediaProtocol.TX_GET_PROGRESS) ?: 0
        if (player !== p || stopped) return // it went away during the calls
        if (playingState == null) {
            if (lastPlaying) worker.postDelayed(tick, TICK_MS)
            return
        }
        val playing = playingState != 0
        lastPlaying = playing
        listener.onRead(SyuMediaRead(kind, song, playing, progress.toLong().coerceAtLeast(0), sampledAt))
        if (playing) worker.postDelayed(tick, TICK_MS)
    }

    /** The int an IPlayer call answers; null when the call failed. */
    private fun readInt(p: IBinder, code: Int): Int? {
        val reply = call(p, code, null) ?: return null
        return try {
            reply.readInt()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$kind: unreadable answer to call $code: $e")
            null
        } finally {
            reply.recycle()
        }
    }

    /** One IPlayer call; the reply past its exception header, or null when it failed. */
    private fun call(p: IBinder, code: Int, arg: IBinder?): Parcel? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SyuMediaProtocol.IPLAYER)
            if (arg != null) data.writeStrongBinder(arg)
            p.transact(code, data, reply, 0)
            reply.readException()
            reply
        } catch (e: RemoteException) {
            // DeadObjectException: onServiceDisconnected() follows.
            Log.w(TAG, "$kind: call $code failed: $e")
            reply.recycle()
            null
        } catch (e: RuntimeException) {
            // Thrown by the player itself, e.g. getPlayingSong() for a song without an id.
            Log.w(TAG, "$kind: call $code failed: $e")
            reply.recycle()
            null
        } finally {
            data.recycle()
        }
    }

    private fun unbindQuietly() {
        try {
            app.unbindService(this)
        } catch (_: RuntimeException) { // IllegalArgumentException: not bound
        }
    }

    /**
     * ICallback, implemented by hand like IPlayer. The player calls it synchronously, inside a lock
     * of its own: it answers at once and only posts a read to the worker.
     */
    private inner class CallbackStub : Binder() {
        init {
            attachInterface(null, SyuMediaProtocol.ICALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(SyuMediaProtocol.ICALLBACK)
                return true
            }
            if (code != SyuMediaProtocol.CB_SONG_CHANGED && code != SyuMediaProtocol.CB_PLAY_STATUS_CHANGED &&
                code != SyuMediaProtocol.CB_PLAY_MODE_CHANGED
            ) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(SyuMediaProtocol.ICALLBACK)
            if (code != SyuMediaProtocol.CB_PLAY_MODE_CHANGED && !stopped) worker.post { refresh() }
            reply?.writeNoException()
            return true
        }
    }

    private companion object {
        const val TAG = "DmtSyuMedia"

        /** com.syu.music sends an update every second while it plays; this keeps that pace. */
        const val TICK_MS = 1000L
        const val REBIND_MS = 5000L
    }
}
