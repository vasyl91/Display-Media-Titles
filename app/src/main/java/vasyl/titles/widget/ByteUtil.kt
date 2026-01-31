package vasyl.titles.widget

object ByteUtil {

    fun indexOf(tag: ByteArray, src: ByteArray, len: Int): Int {
        val tagLen = tag.size
        if (len <= src.size) {
            for (j in 0..(len - tagLen)) {
                var i = 0
                while (i < tagLen && src[j + i] == tag[i]) {
                    if (i == tagLen - 1) {
                        return j
                    }
                    i++
                }
            }
        }
        return -1
    }

    fun lastIndexOf(tag: ByteArray, src: ByteArray, len: Int): Int {
        val tagLen = tag.size
        if (len <= src.size) {
            for (j in (len - tagLen) downTo 0) {
                var i = 0
                while (i < tagLen && src[j + i] == tag[i]) {
                    if (i == tagLen - 1) {
                        return j
                    }
                    i++
                }
            }
        }
        return -1
    }

    fun cutBytes(start: Int, end: Int, src: ByteArray): ByteArray? {
        if (start < 0 || end > src.size || start >= end) return null

        val len = end - start
        val tmp = ByteArray(len)
        System.arraycopy(src, start, tmp, 0, len)
        return tmp
    }
}