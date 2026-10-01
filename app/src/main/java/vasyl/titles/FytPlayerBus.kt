package vasyl.titles

import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One update from the FYT music player (com.syu.music), as delivered to [MusicService]. */
data class FytTrack(
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val durationMs: Long,
    val positionMs: Long,
    val receivedAt: Long = SystemClock.elapsedRealtime(),
) {
    /** Same player state; position and arrival time are ignored. */
    fun sameState(other: FytTrack): Boolean =
        playing == other.playing && title == other.title && artist == other.artist &&
            album == other.album && path == other.path && durationMs == other.durationMs
}

/**
 * In-process channel between [MusicService] (producer) and [NotificationListener] (consumer).
 *
 * Replaces the implicit "titlesReceiver" / "removeReceiver" broadcasts, which were delivered to an
 * EXPORTED receiver: any installed app could spoof track data or crash the listener with a broadcast
 * without extras. Both components live in the same process, so a flow is cheaper and private.
 */
object FytPlayerBus {

    sealed interface Event {
        data class Data(val track: FytTrack) : Event
        data object Remove : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of player events. Events emitted while nobody collects are dropped. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _last = MutableStateFlow<FytTrack?>(null)

    /** An identical update arriving again within this window is not emitted a second time. */
    private const val DUPLICATE_WINDOW_MS = 1_000L

    /** Last emitted update; guarded by this. */
    private var lastEmitted: FytTrack? = null

    /** Last known player state, used to restore the overlay after the listener (re)connects. */
    val last: StateFlow<FytTrack?> = _last.asStateFlow()

    /**
     * On FYT units every update arrives twice: from the player itself ([MusicService]) and as the
     * FYT launcher's "titlesReceiver" broadcast. The second copy (same state, only the position
     * differs) is not emitted again; [last] is refreshed either way.
     */
    fun publish(track: FytTrack) {
        _last.value = track
        val duplicate = synchronized(this) {
            val previous = lastEmitted
            val isDuplicate = previous != null && previous.sameState(track) &&
                track.receivedAt - previous.receivedAt in 0 until DUPLICATE_WINDOW_MS
            if (!isDuplicate) lastEmitted = track
            isDuplicate
        }
        if (!duplicate) _events.tryEmit(Event.Data(track))
    }

    fun publishRemove() {
        synchronized(this) { lastEmitted = null }
        _last.update { it?.copy(playing = false) }
        _events.tryEmit(Event.Remove)
    }
}
