package vasyl.titles.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPauseHoldTest {

    @Test
    fun aPressShowsTheOppositeAtOnce() {
        assertTrue(PlayPauseHold.symbolAfterPress(playingAtPress = false))
        assertFalse(PlayPauseHold.symbolAfterPress(playingAtPress = true))
    }

    @Test
    fun onlyTheLastPressSetsTheRealStateAfterTheHold() {
        val hold = PlayPauseHold()
        val first = hold.press()
        assertTrue(hold.isLatest(first))
        val second = hold.press()
        assertFalse(hold.isLatest(first))
        assertTrue(hold.isLatest(second))
    }
}
