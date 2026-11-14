package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Test

class StockControlsTest {

    @Test
    fun playPauseOfAPlayerDependsOnItsState() {
        assertEquals(SyuMediaProtocol.TX_PAUSE, StockControls.playerTransaction(StockAction.PLAY_PAUSE, playing = true))
        assertEquals(SyuMediaProtocol.TX_PLAY, StockControls.playerTransaction(StockAction.PLAY_PAUSE, playing = false))
    }

    @Test
    fun nextAndPreviousOfAPlayer() {
        assertEquals(SyuMediaProtocol.TX_PLAY_NEXT, StockControls.playerTransaction(StockAction.NEXT, playing = true))
        assertEquals(SyuMediaProtocol.TX_PLAY_LAST, StockControls.playerTransaction(StockAction.PREVIOUS, playing = false))
    }
}
