package vasyl.titles.fyt

/**
 * Behaves like android.os.Parcel when it is read past its end: readInt() gives 0, readString()
 * gives null and dataAvail() says what is left.
 */
class FakeParcel(vararg values: Any?) : ParcelReader {
    private val items = values.toList()
    private var position = 0

    override fun readInt(): Int = (next() as? Int) ?: 0
    override fun readString(): String? = next() as? String
    override fun dataAvail(): Int = (items.size - position).coerceAtLeast(0)

    private fun next(): Any? = if (position < items.size) items[position++] else null.also { position++ }
}
