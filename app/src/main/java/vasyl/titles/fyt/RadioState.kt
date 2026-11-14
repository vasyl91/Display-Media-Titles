package vasyl.titles.fyt

/**
 * The FM / AM station tuned in, from com.syu.ms (radio module). Free of android.* and unit tested.
 *
 * Rules:
 * - the radio plays exactly while the MCU's sound channel is the radio's (1);
 * - the title is the station's name (RDS PS), else its frequency ("FM 101.50"); the artist is the
 *   frequency with its unit when the station has a name, else empty;
 * - a new frequency drops the name of the station before;
 * - without a frequency there is no title, so nothing is shown.
 */
data class RadioState(val freq: Int = 0, val ps: String = "") {

    fun withFreq(newFreq: Int): RadioState = if (newFreq == freq) this else RadioState(newFreq, "")

    fun withPs(newPs: String?): RadioState = copy(ps = newPs.orEmpty())

    val title: String?
        get() = if (freq <= 0) null else StockText.validTag(ps) ?: StockFreq.title(freq)

    val artist: String
        get() = if (freq > 0 && StockText.validTag(ps) != null) StockFreq.titleWithUnit(freq) else ""

    /** Identifies the station for the track's path. */
    val key: String
        get() = "stock-radio:/$freq"

    fun isPlaying(channel: Int): Boolean = channel == MsProtocol.CH_RADIO
}
