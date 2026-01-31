package vasyl.titles

import android.text.InputFilter
import android.text.Spanned

class InputFilterMinMax : InputFilter {
    private var min: Int
    private var max: Int

    @Suppress("unused")
    constructor(min: Int, max: Int) {
        this.min = min
        this.max = max
    }

    constructor(min: String, max: String) {
        this.min = min.toInt()
        this.max = max.toInt()
    }

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): String? {
        try {
            val newVal = dest.subSequence(0, dstart).toString() + 
                         source.subSequence(start, end).toString() + 
                         dest.subSequence(dend, dest.length).toString()
            if (newVal.isEmpty()) return null
            val input = newVal.toInt()
            if (isInRange(min, max, input)) return null
        } catch (nfe: NumberFormatException) {
            nfe.printStackTrace()
        }
        return ""
    }

    private fun isInRange(a: Int, b: Int, c: Int): Boolean {
        return if (b > a) c in a..b else c in b..a
    }
}