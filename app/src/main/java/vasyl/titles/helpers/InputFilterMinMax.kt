package vasyl.titles.helpers

import android.text.InputFilter
import android.text.Spanned

/** Accepts only integers within [min]..[max] (in either order). An empty field is allowed. */
class InputFilterMinMax(private val min: Int, private val max: Int) : InputFilter {

    /** Invalid numbers fall back to 0 instead of crashing with NumberFormatException. */
    constructor(min: String, max: String) : this(min.trim().toIntOrNull() ?: 0, max.trim().toIntOrNull() ?: 0)

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        val newValue = StringBuilder(dest)
            .replace(dstart, dend, source.subSequence(start, end).toString())
            .toString()
        if (newValue.isEmpty()) return null
        // Not a number (or out of Int range): reject the edit.
        val input = newValue.toIntOrNull() ?: return ""
        return if (isInRange(min, max, input)) null else ""
    }

    private fun isInRange(a: Int, b: Int, c: Int): Boolean =
        if (b > a) c in a..b else c in b..a
}
