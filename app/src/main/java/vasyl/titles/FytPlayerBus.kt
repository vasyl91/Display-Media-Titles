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

/** Where an update of the FYT stock sources comes from. */
enum class FytOrigin {
    /** com.syu.music (7862 / 8581), through [MusicService]. */
    SYU_MUSIC,

    /** A launcher's "titlesReceiver" broadcast: a copy of what the stock player sent it. */
    LAUNCHER_RELAY,

    /** com.syu.media's music player (7870). */
    MEDIA_MUSIC,

    /** com.syu.media's internet radio (7870). */
    MEDIA_ONLINE,

    /** BT audio from com.syu.ms (7870). */
    MEDIA_BT,

    /** FM / AM radio from com.syu.ms (7870). */
    RADIO;

    /**
     * A stock source of the FYT 7870: its title and artist come with the update, and its path may
     * be a stream, a key or a file this app cannot read, so no file has to be read for it.
     */
    val is7870Stock: Boolean
        get() = this == MEDIA_MUSIC || this == MEDIA_ONLINE || this == MEDIA_BT || this == RADIO

    companion object {
        val STOCK_7870: Set<FytOrigin> = setOf(MEDIA_MUSIC, MEDIA_ONLINE, MEDIA_BT, RADIO)
    }
}

/**
 * One update from an FYT stock source: com.syu.music as delivered to [MusicService], or (on the
 * FYT 7870) a com.syu.media player, BT audio or the radio.
 */
data class FytTrack(
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val durationMs: Long,
    val positionMs: Long,
    val origin: FytOrigin = FytOrigin.SYU_MUSIC,
    val receivedAt: Long = SystemClock.elapsedRealtime(),
) {
    /** Same player state; position, origin and arrival time are ignored. */
    fun sameState(other: FytTrack): Boolean =
        playing == other.playing && title == other.title && artist == other.artist &&
            album == other.album && path == other.path && durationMs == other.durationMs
}

/**
 * Whether [next] repeats [previous]: the same state within [windowMs] after it. The window is
 * measured with [FytTrack.receivedAt], which a source sets to the moment it read the player, so
 * an update sent once a second is never taken for a repeat.
 */
internal fun isRepeatedUpdate(previous: FytTrack?, next: FytTrack, windowMs: Long): Boolean =
    previous != null && previous.sameState(next) && next.receivedAt - previous.receivedAt in 0 until windowMs

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
            val isDuplicate = isRepeatedUpdate(lastEmitted, track, DUPLICATE_WINDOW_MS)
            if (!isDuplicate) lastEmitted = track
            isDuplicate
        }
        if (!duplicate) _events.tryEmit(Event.Data(track))
    }

    /**
     * The source stopped. With [onlyFrom], nothing happens unless the last update came from one of
     * these origins, so a source never removes what another one shows.
     */
    fun publishRemove(onlyFrom: Set<FytOrigin>? = null) {
        if (onlyFrom != null && _last.value?.origin !in onlyFrom) return
        synchronized(this) { lastEmitted = null }
        _last.update { it?.copy(playing = false) }
        _events.tryEmit(Event.Remove)
    }
}
