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

    private fun cmd(module: Int, code: Int, vararg ints: Int) = StockControls.MsCommand(module, code, ints.toList())

    @Test
    fun theRadioStepsStationsOnlyOnItsChannel() {
        assertEquals(listOf(cmd(1, 0, 0)), StockControls.radioCommands(StockAction.NEXT, MsProtocol.CH_RADIO))
        assertEquals(listOf(cmd(1, 1, 0)), StockControls.radioCommands(StockAction.PREVIOUS, MsProtocol.CH_RADIO))
        assertEquals(emptyList<StockControls.MsCommand>(), StockControls.radioCommands(StockAction.NEXT, MsProtocol.CH_MUSIC))
    }

    @Test
    fun theRadiosPlayPauseSwitchesTheChannel() {
        assertEquals(listOf(cmd(0, 0, 0)), StockControls.radioCommands(StockAction.PLAY_PAUSE, MsProtocol.CH_RADIO))
        assertEquals(listOf(cmd(0, 0, 1)), StockControls.radioCommands(StockAction.PLAY_PAUSE, MsProtocol.CH_NONE))
    }

    @Test
    fun btTogglesOnItsChannelAndOtherwiseTakesItFirst() {
        assertEquals(listOf(cmd(2, 0)), StockControls.btCommands(StockAction.PREVIOUS, MsProtocol.CH_MUSIC))
        assertEquals(listOf(cmd(2, 1)), StockControls.btCommands(StockAction.NEXT, MsProtocol.CH_BT))
        assertEquals(listOf(cmd(2, 2)), StockControls.btCommands(StockAction.PLAY_PAUSE, MsProtocol.CH_BT))
        assertEquals(
            listOf(cmd(0, 0, 3), cmd(2, 27)),
            StockControls.btCommands(StockAction.PLAY_PAUSE, MsProtocol.CH_THIRD_PARTY),
        )
    }
}
