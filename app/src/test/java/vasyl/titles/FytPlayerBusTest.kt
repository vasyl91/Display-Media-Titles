package vasyl.titles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FytPlayerBusTest {

    private fun track(at: Long, playing: Boolean = true, title: String = "Song", origin: FytOrigin = FytOrigin.SYU_MUSIC) =
        FytTrack(playing, title, "Artist", "Album", "/storage/usb/Song.mp3", 1000L, 0L, origin, at)

    @Test
    fun theSameStateWithinTheWindowIsARepeat() {
        assertTrue(isRepeatedUpdate(track(1000), track(1500), 1000))
    }

    @Test
    fun anUpdateOnceASecondIsNoRepeat() {
        assertFalse(isRepeatedUpdate(track(1000), track(2000), 1000))
    }

    @Test
    fun theOriginDoesNotMatter() {
        // A launcher's relay of com.syu.music's update is the same update.
        assertTrue(isRepeatedUpdate(track(1000), track(1200, origin = FytOrigin.LAUNCHER_RELAY), 1000))
    }

    @Test
    fun anotherStateIsNoRepeat() {
        assertFalse(isRepeatedUpdate(track(1000), track(1100, playing = false), 1000))
        assertFalse(isRepeatedUpdate(track(1000), track(1100, title = "Other"), 1000))
        assertFalse(isRepeatedUpdate(null, track(1100), 1000))
    }

    @Test
    fun anEarlierTimeIsNoRepeat() {
        assertFalse(isRepeatedUpdate(track(2000), track(1000), 1000))
    }

    @Test
    fun only7870OriginsAreStockOf7870() {
        assertFalse(FytOrigin.SYU_MUSIC.is7870Stock)
        assertFalse(FytOrigin.LAUNCHER_RELAY.is7870Stock)
        assertTrue(FytOrigin.STOCK_7870.all { it.is7870Stock })
    }
}
