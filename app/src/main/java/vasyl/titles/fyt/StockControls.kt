package vasyl.titles.fyt

/** A press of one of the widget's buttons. */
enum class StockAction { PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * Which command a widget button sends to a stock source of the FYT 7870. Free of android.* and unit
 * tested. The commands are those of the FYT Launcher Mod (com.fyt.car.SyuMediaClient.control).
 *
 * Rules:
 * - com.syu.media's players have no toggle: play/pause asks the player's state first and then
 *   pauses (TX 7) or plays (TX 1). TX 1 on a track that plays would start it again.
 * - Next and previous are TX 6 and TX 5.
 * - The radio (FYT Launcher Mod, StockSources.control): next / previous step through the station
 *   list and are only sent while the radio is on its channel; play/pause switches the MCU's sound
 *   channel off (0) or to the radio (1).
 * - BT (StockSources.controlBt): previous and next go to the phone; play/pause toggles while BT
 *   is on its channel, and from another channel switches to BT first and then asks to play.
 * - Nothing is ever started without a press: the titles never resume a source by themselves.
 */
object StockControls {

    /** One IRemoteModule.cmd of com.syu.ms. */
    data class MsCommand(val module: Int, val code: Int, val ints: List<Int> = emptyList())

    /** The IPlayer transaction for [action]; [playing] is the player's state read just before. */
    fun playerTransaction(action: StockAction, playing: Boolean): Int = when (action) {
        StockAction.PLAY_PAUSE -> if (playing) SyuMediaProtocol.TX_PAUSE else SyuMediaProtocol.TX_PLAY
        StockAction.NEXT -> SyuMediaProtocol.TX_PLAY_NEXT
        StockAction.PREVIOUS -> SyuMediaProtocol.TX_PLAY_LAST
    }

    /** The radio's commands for [action] on MCU channel [channel]; empty when there is nothing to do. */
    fun radioCommands(action: StockAction, channel: Int): List<MsCommand> {
        val onAir = channel == MsProtocol.CH_RADIO
        return when (action) {
            StockAction.NEXT ->
                if (onAir) listOf(MsCommand(MsProtocol.MODULE_RADIO, MsProtocol.CMD_RADIO_NEXT, listOf(0))) else emptyList()
            StockAction.PREVIOUS ->
                if (onAir) listOf(MsCommand(MsProtocol.MODULE_RADIO, MsProtocol.CMD_RADIO_PREV, listOf(0))) else emptyList()
            StockAction.PLAY_PAUSE -> listOf(
                MsCommand(
                    MsProtocol.MODULE_MAIN, MsProtocol.CMD_MAIN_APP_ID,
                    listOf(if (onAir) MsProtocol.CH_NONE else MsProtocol.CH_RADIO),
                )
            )
        }
    }

    /** BT's commands for [action] on MCU channel [channel]. */
    fun btCommands(action: StockAction, channel: Int): List<MsCommand> = when (action) {
        StockAction.PREVIOUS -> listOf(MsCommand(MsProtocol.MODULE_BT, MsProtocol.CMD_BT_PREV))
        StockAction.NEXT -> listOf(MsCommand(MsProtocol.MODULE_BT, MsProtocol.CMD_BT_NEXT))
        StockAction.PLAY_PAUSE ->
            if (channel == MsProtocol.CH_BT) {
                listOf(MsCommand(MsProtocol.MODULE_BT, MsProtocol.CMD_BT_PLAY_PAUSE))
            } else {
                listOf(
                    MsCommand(MsProtocol.MODULE_MAIN, MsProtocol.CMD_MAIN_APP_ID, listOf(MsProtocol.CH_BT)),
                    MsCommand(MsProtocol.MODULE_BT, MsProtocol.CMD_BT_PLAY),
                )
            }
    }
}
