package vasyl.titles.widget

import android.graphics.Typeface
import java.util.Locale

object FuncUtils {

    /**
     * Legacy 8-bit code page per language, used for tags that are neither ASCII nor valid UTF-8.
     * Windows code pages are used because tags are mostly written by Windows software
     * (e.g. Polish tags are CP1250, not ISO-8859-2; Cyrillic tags are CP1251, not ISO-8859-5;
     * Baltic languages were mapped to the Central European ISO-8859-2 before).
     */
    private val LOCALE_TO_CHARSET_MAP: Map<String, String> = hashMapOf(
        // Western European
        "ca" to "windows-1252",
        "da" to "windows-1252",
        "de" to "windows-1252",
        "en" to "windows-1252",
        "es" to "windows-1252",
        "eu" to "windows-1252",
        "fi" to "windows-1252",
        "fr" to "windows-1252",
        "ga" to "windows-1252",
        "gl" to "windows-1252",
        "is" to "windows-1252",
        "it" to "windows-1252",
        "nb" to "windows-1252",
        "nl" to "windows-1252",
        "nn" to "windows-1252",
        "no" to "windows-1252",
        "pt" to "windows-1252",
        "sv" to "windows-1252",
        // Central European
        "bs" to "windows-1250",
        "cs" to "windows-1250",
        "hr" to "windows-1250",
        "hu" to "windows-1250",
        "pl" to "windows-1250",
        "ro" to "windows-1250",
        "sh" to "windows-1250",
        "sk" to "windows-1250",
        "sl" to "windows-1250",
        "sq" to "windows-1250",
        // Cyrillic
        "be" to "windows-1251",
        "bg" to "windows-1251",
        "kk" to "windows-1251",
        "mk" to "windows-1251",
        "ru" to "windows-1251",
        "sr" to "windows-1251",
        "uk" to "windows-1251",
        // Baltic
        "et" to "windows-1257",
        "lt" to "windows-1257",
        "lv" to "windows-1257",
        // Others
        "ar" to "windows-1256",
        "fa" to "windows-1256",
        "el" to "windows-1253",
        "he" to "windows-1255",
        "iw" to "windows-1255", // legacy code for Hebrew still returned by Locale on old Java
        "ja" to "Shift_JIS",
        "ko" to "EUC-KR",
        "th" to "TIS-620",
        "tr" to "windows-1254",
        "vi" to "windows-1258",
        "zh" to "GB18030",
        "zh_TW" to "Big5",
        "zh_HK" to "Big5",
    )

    private const val DEFAULT_CHARSET = "windows-1252"

    /** Typeface cache (by font path). Access it on the main thread only. */
    val mTypeFaces: HashMap<String, Typeface> = HashMap()

    fun getCharset(locale: Locale): String {
        // Full locale ("zh_TW") first, then the language only.
        LOCALE_TO_CHARSET_MAP[locale.toString()]?.let { return it }
        LOCALE_TO_CHARSET_MAP["${locale.language}_${locale.country}"]?.let { return it }
        LOCALE_TO_CHARSET_MAP[locale.language]?.let { return it }
        return DEFAULT_CHARSET
    }

    fun check(ints: IntArray?, index: Int): Boolean =
        ints != null && index >= 0 && ints.size > index

    /** Accepts any array type (Array<Any> was invariant and rejected e.g. Array<String>). */
    fun check(objs: Array<*>?, index: Int): Boolean =
        objs != null && index >= 0 && objs.size > index
}
