package vasyl.titles.fyt

import vasyl.titles.FytOrigin
import vasyl.titles.FytTrack

/**
 * Builds the [FytTrack] of a stock source of the FYT 7870. Free of android.* (apart from
 * FytTrack's default arrival time, which is always given here) and unit tested.
 *
 * Rule: no track without a title. A source without one (no track loaded, BT without metadata,
 * a radio before its frequency) gives null, so nothing is shown for it and no title on screen is
 * replaced by an empty one. Placeholder texts ("unknown" & co.) are no value.
 */
object StockTracks {

    fun player(kind: StockKind, song: SyuMediaSong, playing: Boolean, progressMs: Long, atMs: Long): FytTrack? {
        val title = StockText.validTag(song.title) ?: StockText.fileBaseName(song.path) ?: return null
        return FytTrack(
            playing = playing,
            title = title,
            artist = StockText.validTag(song.artist).orEmpty(),
            album = StockText.validTag(song.album).orEmpty(),
            path = song.path,
            durationMs = song.durationMs,
            positionMs = progressMs.coerceAtLeast(0),
            origin = originOf(kind),
            receivedAt = atMs,
        )
    }

    fun radio(state: RadioState, channel: Int, atMs: Long): FytTrack? {
        val title = state.title ?: return null
        return FytTrack(state.isPlaying(channel), title, state.artist, "", state.key, 0L, 0L, FytOrigin.RADIO, atMs)
    }

    fun bt(state: BtState, channel: Int, atMs: Long): FytTrack? {
        val title = state.shownTitle ?: return null
        return FytTrack(
            state.isPlaying(channel), title, state.shownArtist, StockText.validTag(state.album).orEmpty(),
            state.key, 0L, 0L, FytOrigin.MEDIA_BT, atMs,
        )
    }

    fun originOf(kind: StockKind): FytOrigin = when (kind) {
        StockKind.MEDIA_MUSIC -> FytOrigin.MEDIA_MUSIC
        StockKind.MEDIA_ONLINE -> FytOrigin.MEDIA_ONLINE
        StockKind.MEDIA_BT -> FytOrigin.MEDIA_BT
        StockKind.RADIO -> FytOrigin.RADIO
    }
}
