package vasyl.titles.widget

import android.graphics.Typeface
import java.util.Locale

object FuncUtils {

    private val LOCALE_TO_CHARSET_MAP = hashMapOf(
        "ar" to "ISO-8859-6",
        "be" to "ISO-8859-5",
        "bg" to "ISO-8859-5",
        "ca" to "ISO-8859-1",
        "cs" to "ISO-8859-2",
        "da" to "ISO-8859-1",
        "de" to "ISO-8859-1",
        "el" to "ISO-8859-7",
        "es" to "ISO-8859-1",
        "et" to "ISO-8859-1",
        "fi" to "ISO-8859-1",
        "fr" to "ISO-8859-1",
        "hr" to "ISO-8859-2",
        "hu" to "ISO-8859-2",
        "is" to "ISO-8859-1",
        "it" to "ISO-8859-1",
        "iw" to "ISO-8859-8",
        "ja" to "Shift_JIS",
        "ko" to "EUC-KR",
        "lt" to "ISO-8859-2",
        "lv" to "ISO-8859-2",
        "mk" to "ISO-8859-5",
        "nl" to "ISO-8859-1",
        "no" to "ISO-8859-1",
        "pl" to "ISO-8859-2",
        "pt" to "ISO-8859-1",
        "ro" to "ISO-8859-2",
        "ru" to "ISO-8859-5",
        "sh" to "ISO-8859-5",
        "sk" to "ISO-8859-2",
        "sl" to "ISO-8859-2",
        "sq" to "ISO-8859-2",
        "sr" to "ISO-8859-5",
        "sv" to "ISO-8859-1",
        "tr" to "ISO-8859-9",
        "uk" to "ISO-8859-5",
    )

    val mTypeFaces: HashMap<String, Typeface> = HashMap()

    fun getCharset(locale: Locale): String {
        // First try full locale string
        LOCALE_TO_CHARSET_MAP[locale.toString()]?.let { return it }

        // Then try language only
        LOCALE_TO_CHARSET_MAP[locale.language]?.let { return it }

        // Default
        return "GB18030"
    }

    fun check(ints: IntArray?, index: Int): Boolean {
        return ints != null && ints.size > index
    }

    fun check(objs: Array<Any>?, index: Int): Boolean {
        return objs != null && objs.size > index
    }
}