package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyuMediaSongTest {

    private fun song(
        id: Int = 7, title: String? = "Song", displayName: String? = "Song.mp3", artist: String? = "Artist",
        album: String? = "Album", path: String? = "/storage/usb/Song.mp3", duration: Int = 180_000,
        vararg tail: Any?,
    ) = FakeParcel(1, id, title, displayName, artist, album, path, "/storage/usb", duration, 0, 1234, *tail)

    @Test
    fun theFieldsAreReadInTheOrderOfMediaSource() {
        val s = SyuMediaSong.read(song())!!
        assertEquals(7, s.id)
        assertEquals("Song", s.title)
        assertEquals("Artist", s.artist)
        assertEquals("Album", s.album)
        assertEquals("/storage/usb/Song.mp3", s.path)
        assertEquals(180_000L, s.durationMs)
        assertFalse(s.isPlaceholder)
    }

    @Test
    fun aRadioSourceTailIsIgnored() {
        val s = SyuMediaSong.read(
            song(1, "Radio One", "", "", "", "http://stream.example/one", 0, "Pop", "PL", "", "", "icon_one")
        )!!
        assertEquals("Radio One", s.title)
        assertEquals("http://stream.example/one", s.path)
        assertEquals(0L, s.durationMs)
    }

    @Test
    fun withoutATitleTagTheDisplayNameIsTheTitle() {
        assertEquals("Song.mp3", SyuMediaSong.read(song(title = ""))!!.title)
        assertEquals("Song.mp3", SyuMediaSong.read(song(title = null))!!.title)
    }

    @Test
    fun missingTextsAreEmptyNotNull() {
        val s = SyuMediaSong.read(song(title = null, displayName = null, artist = null, album = null, path = null))!!
        assertEquals("", s.title)
        assertEquals("", s.artist)
        assertEquals("", s.album)
        assertEquals("", s.path)
    }

    @Test
    fun noSongAtAllIsNull() {
        assertNull(SyuMediaSong.read(FakeParcel(0)))
    }

    @Test
    fun thePlaceholderIsNoTrack() {
        assertTrue(SyuMediaSong.read(song(-1, "unknown", "unknown", "unknown", "unknown", "unknown", 0))!!.isPlaceholder)
        assertTrue(SyuMediaSong.read(song(id = 3, path = "unknown"))!!.isPlaceholder)
        assertTrue(SyuMediaSong.read(song(id = 3, path = ""))!!.isPlaceholder)
    }

    @Test
    fun aNegativeDurationIsZero() {
        assertEquals(0L, SyuMediaSong.read(song(duration = -5))!!.durationMs)
    }

    @Test
    fun theSameTrackHasTheSameIdAndPath() {
        val a = SyuMediaSong.read(song())!!
        assertTrue(a.isSameTrack(SyuMediaSong.read(song())))
        assertFalse(a.isSameTrack(SyuMediaSong.read(song(id = 8))))
        assertFalse(a.isSameTrack(null))
    }
}
