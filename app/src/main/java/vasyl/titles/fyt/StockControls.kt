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
 * - Nothing is ever started without a press: the titles never resume a source by themselves.
 */
object StockControls {

    /** The IPlayer transaction for [action]; [playing] is the player's state read just before. */
    fun playerTransaction(action: StockAction, playing: Boolean): Int = when (action) {
        StockAction.PLAY_PAUSE -> if (playing) SyuMediaProtocol.TX_PAUSE else SyuMediaProtocol.TX_PLAY
        StockAction.NEXT -> SyuMediaProtocol.TX_PLAY_NEXT
        StockAction.PREVIOUS -> SyuMediaProtocol.TX_PLAY_LAST
    }
}
