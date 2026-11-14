package vasyl.titles.fyt

import java.util.Locale

/**
 * Radio frequencies as com.syu.ms reports them (radio module, code 1): FM in units of 10 kHz
 * (10150 is 101.50 MHz), AM in kHz (1008). Above 5000 is FM, as FYT's radio widget reads it.
 */
object StockFreq {

    fun isFm(freq: Int): Boolean = freq > 5000

    /** "101.50" / "1008"; integers only, a float would turn 10150 into 101.5 at times. */
    fun format(freq: Int): String =
        if (isFm(freq)) String.format(Locale.US, "%d.%02d", freq / 100, freq % 100) else freq.toString()

    /** "FM 101.50" / "AM 1008": a station without a name. */
    fun title(freq: Int): String = (if (isFm(freq)) "FM " else "AM ") + format(freq)

    /** "FM 101.50 MHz" / "AM 1008 kHz". */
    fun titleWithUnit(freq: Int): String = title(freq) + if (isFm(freq)) " MHz" else " kHz"
}
