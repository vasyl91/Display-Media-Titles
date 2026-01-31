package vasyl.titles.excludeapps

open class ItemInfo {

    companion object {
        const val NO_ID = -1L
    }

    var id: Long = NO_ID

    var itemType: Int = 0

    var container: Long = NO_ID

    var screenId: Long = -1

    var cellX: Int = -1
    var cellY: Int = -1

    var spanX: Int = 1
    var spanY: Int = 1

    var title: CharSequence? = null

    var dropPos: IntArray? = null

    constructor()

    override fun toString(): String {
        return "Item(" +
                "id=$id " +
                "type=$itemType " +
                "container=$container " +
                "screen=$screenId " +
                "cellX=$cellX " +
                "cellY=$cellY " +
                "spanX=$spanX " +
                "spanY=$spanY " +
                "dropPos=$dropPos" +
                ")"
    }
}
