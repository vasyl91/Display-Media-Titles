package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vasyl.titles.FytOrigin

class StockTracksTest {

    private fun song(title: String = "Song", artist: String = "Artist", path: String = "/storage/usb/Song.mp3") =
        SyuMediaSong(3, title, artist, "Album", path, 180_000L)

    @Test
    fun aPlayerTrackCarriesWhatThePlayerSaid() {
        val t = StockTracks.player(StockKind.MEDIA_MUSIC, song(), true, 5000, 1234)!!
        assertEquals("Song", t.title)
        assertEquals("Artist", t.artist)
        assertEquals("Album", t.album)
        assertEquals(180_000L, t.durationMs)
        assertEquals(5000L, t.positionMs)
        assertTrue(t.playing)
        assertEquals(FytOrigin.MEDIA_MUSIC, t.origin)
        assertEquals(1234L, t.receivedAt) // the moment it was read, not when it was handed over
    }

    @Test
    fun withoutATitleTheFileNameIsTheTitle() {
        assertEquals("Song", StockTracks.player(StockKind.MEDIA_MUSIC, song(title = "unknown"), true, 0, 0)!!.title)
    }

    @Test
    fun aStreamWithoutATitleIsNoTrack() {
        assertNull(StockTracks.player(StockKind.MEDIA_ONLINE, song(title = "", path = "http://x/y"), true, 0, 0))
    }

    @Test
    fun anUnknownArtistIsEmpty() {
        assertEquals("", StockTracks.player(StockKind.MEDIA_ONLINE, song(artist = "unknown"), true, 0, 0)!!.artist)
    }

    @Test
    fun theRadioPlaysOnItsChannelOnly() {
        val radio = RadioState().withFreq(10150).withPs("RMF FM")
        val on = StockTracks.radio(radio, MsProtocol.CH_RADIO, 0)!!
        assertTrue(on.playing)
        assertEquals("RMF FM", on.title)
        assertEquals(FytOrigin.RADIO, on.origin)
        assertFalse(StockTracks.radio(radio, MsProtocol.CH_THIRD_PARTY, 0)!!.playing)
        assertNull(StockTracks.radio(RadioState(), MsProtocol.CH_RADIO, 0))
    }

    @Test
    fun btWithoutATitleIsNoTrack() {
        assertNull(StockTracks.bt(BtState("", "Artist", phonePlaying = true), MsProtocol.CH_BT, 0))
        val t = StockTracks.bt(BtState("Song", "Artist", phonePlaying = true), MsProtocol.CH_BT, 0)!!
        assertTrue(t.playing)
        assertEquals(FytOrigin.MEDIA_BT, t.origin)
        assertTrue(t.origin.is7870Stock)
    }
}
