package vasyl.titles.fyt

/**
 * Texts of the FYT stock sources. Free of android.* and unit tested.
 *
 * Rule: an empty text and the placeholders the players use ("null", "unknown", "<unknown>", in
 * any case) are no value; a value is trimmed.
 */
object StockText {

    fun validTag(value: String?): String? {
        val text = value?.trim() ?: return null
        if (text.isEmpty() || text == "null" || text.equals("unknown", ignoreCase = true) ||
            text.equals("<unknown>", ignoreCase = true)
        ) {
            return null
        }
        return text
    }

    /**
     * com.syu.music's "no title": "Unknown" (any case) or "<unknown>". Only the whole text counts,
     * so a real title that contains the word ("Unknown Pleasures") is a title.
     */
    fun isUnknownPlaceholder(value: String?): Boolean {
        val text = value?.trim() ?: return false
        return text.equals("unknown", ignoreCase = true) || text.equals("<unknown>", ignoreCase = true)
    }

    /** A file of this device: an absolute path, not a URL ("http://") or a key ("stock-radio:/"). */
    fun isLocalFile(path: String?): Boolean =
        path != null && path.length > 1 && path[0] == '/' && path[1] != '/'

    /** The file's name without its extension (all of it without one); null for no local file. */
    fun fileBaseName(path: String?): String? {
        if (!isLocalFile(path)) return null
        val name = path!!.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return base.ifEmpty { null }
    }
}
