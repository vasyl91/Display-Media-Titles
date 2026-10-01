package vasyl.titles.widget

object ByteUtil {

    /** Index of the first occurrence of [tag] within the first [len] bytes of [src], or -1. */
    fun indexOf(tag: ByteArray, src: ByteArray, len: Int): Int {
        val tagLen = tag.size
        if (tagLen == 0 || len > src.size || len < tagLen) return -1
        outer@ for (j in 0..(len - tagLen)) {
            for (i in 0 until tagLen) {
                if (src[j + i] != tag[i]) continue@outer
            }
            return j
        }
        return -1
    }

    /** Index of the last occurrence of [tag] within the first [len] bytes of [src], or -1. */
    fun lastIndexOf(tag: ByteArray, src: ByteArray, len: Int): Int {
        val tagLen = tag.size
        if (tagLen == 0 || len > src.size || len < tagLen) return -1
        outer@ for (j in (len - tagLen) downTo 0) {
            for (i in 0 until tagLen) {
                if (src[j + i] != tag[i]) continue@outer
            }
            return j
        }
        return -1
    }

    /** Copy of src[start, end), or null for an invalid range. */
    fun cutBytes(start: Int, end: Int, src: ByteArray): ByteArray? {
        if (start < 0 || end > src.size || start >= end) return null
        return src.copyOfRange(start, end)
    }
}
