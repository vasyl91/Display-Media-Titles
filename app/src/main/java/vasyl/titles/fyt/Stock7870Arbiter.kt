package vasyl.titles.fyt

/** The stock sources of the FYT 7870 and the MCU sound channel each one plays on. */
enum class StockKind(val channel: Int, val overlay: Boolean) {
    /** com.syu.media, local / USB music: IPlayer of its MusicService. */
    MEDIA_MUSIC(MsProtocol.CH_MUSIC, false),

    /** com.syu.media, internet radio: IPlayer of its OnlineRadioService. */
    MEDIA_ONLINE(MsProtocol.CH_ONLINE, false),

    /** The phone's BT audio, read from com.syu.ms (BT module). */
    MEDIA_BT(MsProtocol.CH_BT, true),

    /** FM / AM radio, read from com.syu.ms (radio module). */
    RADIO(MsProtocol.CH_RADIO, true);

    val isPlayer: Boolean
        get() = !overlay
}

/**
 * Which stock source of the FYT 7870 is the one shown. Free of android.* and unit tested; called
 * on one thread only, so the reads of every source arrive in order.
 *
 * Rules (from the FYT Launcher Mod's StockKindArbiter and StockSources.acceptSyuMedia, without
 * its controls):
 * 1. The MCU's sound channel decides: 8 music, 7 internet radio, 3 BT, 1 radio. Any other channel
 *    (0, 10 = another app, ...) changes nothing. Without com.syu.ms the channel is unknown and
 *    the music player is shown first.
 * 2. A read of the player shown is always taken. A read older than the last one of the same
 *    player is dropped.
 * 3. A read "playing" of the other player makes it the one shown before the channel follows, but
 *    only if: it was not taken within [settleMs] after the channel changed the source shown (a
 *    player paused by the channel was read as playing a moment earlier); BT or the radio shown is
 *    not on its own channel (what is heard is theirs); and the player shown does not play, or the
 *    read is a start (the read before it of that player, since it was connected, said "not
 *    playing"). The last "playing" before a pause is never a start, so it never takes over, and
 *    two players that both keep playing do not take turns.
 * 4. A player whose service went away loses its history; if it was the one shown, the caller
 *    removes what was shown.
 */
class Stock7870Arbiter(private val settleMs: Long = PLAYER_SETTLE_MS) {

    var shown: StockKind = StockKind.MEDIA_MUSIC
        private set

    /** The MCU's sound channel, [CHANNEL_UNKNOWN] while com.syu.ms has not said it. */
    var channel: Int = CHANNEL_UNKNOWN
        private set

    private var channelChangedShownAt: Long? = null
    private val known = BooleanArray(2)
    private val playing = BooleanArray(2)
    private val lastAt = LongArray(2) { Long.MIN_VALUE }

    /** @return whether the source shown is another one now */
    fun onChannel(newChannel: Int, atMs: Long): Boolean {
        channel = newChannel
        val kind = kindForChannel(newChannel) ?: return false
        if (kind == shown) return false
        shown = kind
        channelChangedShownAt = atMs
        return true
    }

    /** com.syu.ms went away: the channel is not known any more. */
    fun onChannelLost() {
        channel = CHANNEL_UNKNOWN
    }

    /**
     * A read of a com.syu.media player.
     *
     * @return whether it is for the source shown (which it may have just become)
     */
    fun onPlayerRead(kind: StockKind, isPlaying: Boolean, sampledAtMs: Long): Boolean {
        val k = index(kind)
        if (sampledAtMs < lastAt[k]) return false
        lastAt[k] = sampledAtMs
        val start = known[k] && !playing[k] && isPlaying
        known[k] = true
        playing[k] = isPlaying

        if (kind == shown) return true
        if (!isPlaying) return false
        val changedAt = channelChangedShownAt
        if (changedAt != null && sampledAtMs < changedAt + settleMs) return false
        if (shown.overlay && channel == shown.channel) return false
        if (shown.isPlayer && playing[index(shown)] && !start) return false
        shown = kind
        return true
    }

    /** @return whether the player was the source shown */
    fun onPlayerGone(kind: StockKind): Boolean {
        val k = index(kind)
        known[k] = false
        playing[k] = false
        lastAt[k] = Long.MIN_VALUE
        return kind == shown
    }

    private fun index(kind: StockKind): Int = when (kind) {
        StockKind.MEDIA_MUSIC -> 0
        StockKind.MEDIA_ONLINE -> 1
        else -> throw IllegalArgumentException("$kind is no player")
    }

    companion object {
        const val CHANNEL_UNKNOWN = -1

        /** As PLAYER_SETTLE_MS of the FYT Launcher Mod. */
        const val PLAYER_SETTLE_MS = 1500L

        fun kindForChannel(channel: Int): StockKind? = when (channel) {
            MsProtocol.CH_MUSIC -> StockKind.MEDIA_MUSIC
            MsProtocol.CH_ONLINE -> StockKind.MEDIA_ONLINE
            MsProtocol.CH_BT -> StockKind.MEDIA_BT
            MsProtocol.CH_RADIO -> StockKind.RADIO
            else -> null
        }
    }
}
