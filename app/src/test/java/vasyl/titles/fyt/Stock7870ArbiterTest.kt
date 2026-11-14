package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vasyl.titles.fyt.StockKind.MEDIA_BT
import vasyl.titles.fyt.StockKind.MEDIA_MUSIC
import vasyl.titles.fyt.StockKind.MEDIA_ONLINE
import vasyl.titles.fyt.StockKind.RADIO

class Stock7870ArbiterTest {

    private val a = Stock7870Arbiter()

    @Test
    fun theChannelsStandForTheSources() {
        assertTrue(a.onChannel(MsProtocol.CH_RADIO, 0)); assertEquals(RADIO, a.shown)
        assertTrue(a.onChannel(MsProtocol.CH_BT, 0)); assertEquals(MEDIA_BT, a.shown)
        assertTrue(a.onChannel(MsProtocol.CH_ONLINE, 0)); assertEquals(MEDIA_ONLINE, a.shown)
        assertTrue(a.onChannel(MsProtocol.CH_MUSIC, 0)); assertEquals(MEDIA_MUSIC, a.shown)
    }

    @Test
    fun otherChannelsChangeNothing() {
        a.onChannel(MsProtocol.CH_RADIO, 0)
        assertFalse(a.onChannel(MsProtocol.CH_THIRD_PARTY, 10))
        assertFalse(a.onChannel(MsProtocol.CH_NONE, 20))
        assertEquals(RADIO, a.shown)
        assertEquals(MsProtocol.CH_NONE, a.channel)
    }

    @Test
    fun withoutAChannelTheMusicPlayerIsShown() {
        assertEquals(MEDIA_MUSIC, a.shown)
        assertEquals(Stock7870Arbiter.CHANNEL_UNKNOWN, a.channel)
    }

    @Test
    fun aStartOfTheOtherPlayerTakesOverAndAStaleReadDoesNotComeBack() {
        assertTrue(a.onPlayerRead(MEDIA_MUSIC, true, 1000))
        assertFalse(a.onPlayerRead(MEDIA_ONLINE, false, 1100)) // not shown, paused: ignored
        assertTrue(a.onPlayerRead(MEDIA_ONLINE, true, 2000)) // a start: takes over
        assertEquals(MEDIA_ONLINE, a.shown)
        assertFalse(a.onPlayerRead(MEDIA_MUSIC, true, 2100)) // its last read before pausing
        assertEquals(MEDIA_ONLINE, a.shown)
        assertFalse(a.onPlayerRead(MEDIA_MUSIC, false, 2200))
    }

    @Test
    fun aPlayerTakesOverFromOneThatPaused() {
        a.onPlayerRead(MEDIA_MUSIC, false, 1000)
        assertTrue(a.onPlayerRead(MEDIA_ONLINE, true, 2000)) // first read, but the shown one is paused
        assertEquals(MEDIA_ONLINE, a.shown)
    }

    @Test
    fun aFirstReadIsNoStartWhileTheShownPlayerPlays() {
        a.onPlayerRead(MEDIA_MUSIC, true, 1000)
        assertFalse(a.onPlayerRead(MEDIA_ONLINE, true, 1100))
        a.onPlayerRead(MEDIA_MUSIC, false, 1200)
        assertTrue(a.onPlayerRead(MEDIA_ONLINE, true, 1300))
    }

    @Test
    fun twoPlayersThatKeepPlayingDoNotTakeTurns() {
        a.onPlayerRead(MEDIA_MUSIC, true, 1000)
        a.onPlayerRead(MEDIA_ONLINE, false, 1000)
        a.onPlayerRead(MEDIA_ONLINE, true, 2000)
        for (t in 3000L..9000L step 1000) {
            assertFalse(a.onPlayerRead(MEDIA_MUSIC, true, t))
            assertTrue(a.onPlayerRead(MEDIA_ONLINE, true, t))
        }
        assertEquals(MEDIA_ONLINE, a.shown)
    }

    @Test
    fun anOlderReadIsDropped() {
        a.onPlayerRead(MEDIA_MUSIC, true, 5000)
        assertFalse(a.onPlayerRead(MEDIA_MUSIC, false, 4000))
    }

    @Test
    fun noTakeOverJustAfterTheChannelMovedTheSource() {
        a.onPlayerRead(MEDIA_MUSIC, true, 1000)
        a.onChannel(MsProtocol.CH_ONLINE, 2000) // the music player is paused by the channel
        assertFalse(a.onPlayerRead(MEDIA_MUSIC, true, 2500)) // read before its pause
        assertEquals(MEDIA_ONLINE, a.shown)
        a.onPlayerRead(MEDIA_ONLINE, false, 3600)
        a.onPlayerRead(MEDIA_MUSIC, false, 3600)
        assertTrue(a.onPlayerRead(MEDIA_MUSIC, true, 3700))
    }

    @Test
    fun aPlayerDoesNotTakeOverFromAnOverlayOnItsChannel() {
        a.onChannel(MsProtocol.CH_RADIO, 0)
        assertFalse(a.onPlayerRead(MEDIA_MUSIC, true, 5000))
        assertEquals(RADIO, a.shown)
        a.onChannel(MsProtocol.CH_THIRD_PARTY, 6000) // the radio is not heard any more
        assertTrue(a.onPlayerRead(MEDIA_MUSIC, true, 8000))
        assertEquals(MEDIA_MUSIC, a.shown)
    }

    @Test
    fun theShownPlayerGoingAwayIsReported() {
        a.onPlayerRead(MEDIA_MUSIC, true, 1000)
        assertTrue(a.onPlayerGone(MEDIA_MUSIC))
        assertFalse(a.onPlayerGone(MEDIA_ONLINE))
        assertTrue(a.onPlayerRead(MEDIA_ONLINE, true, 2000))
        assertEquals(MEDIA_ONLINE, a.shown)
    }

    @Test
    fun aPlayerThatCameBackReadsAsNewAgain() {
        a.onPlayerRead(MEDIA_MUSIC, true, 5000)
        a.onPlayerGone(MEDIA_MUSIC)
        assertTrue(a.onPlayerRead(MEDIA_MUSIC, true, 100)) // a new connection: older times are fine
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyPlayersAreRead() {
        a.onPlayerRead(RADIO, true, 0)
    }
}
