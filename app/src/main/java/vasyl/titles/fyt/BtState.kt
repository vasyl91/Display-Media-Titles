package vasyl.titles.fyt

/**
 * The phone's song over FYT's Bluetooth, from com.syu.ms (BT module). Free of android.* and unit
 * tested.
 *
 * Rules:
 * - BT audio plays only while the phone reports it plays (code 13 == 1) and the MCU's sound
 *   channel is BT's (3); the phone may keep "playing" while another source is heard;
 * - without a title there is nothing to show: no placeholder text is made up.
 */
data class BtState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val phonePlaying: Boolean = false,
) {
    val shownTitle: String?
        get() = StockText.validTag(title)

    val shownArtist: String
        get() = StockText.validTag(artist).orEmpty()

    /** Identifies the song for the track's path. */
    val key: String
        get() = "stock-bt:/" + Integer.toHexString(listOf(title, artist, album).hashCode())

    fun isPlaying(channel: Int): Boolean = phonePlaying && channel == MsProtocol.CH_BT
}
