package vasyl.titles.widget

import java.util.Locale

/**
 * Picks the picture of a music folder for a file that has no cover of its own. Free of android.*
 * and unit tested.
 *
 * Rules: only images (.jpg, .jpeg, .png, .webp) count, names in any case. The order is the common
 * one of players and rippers: "cover", "folder", "front", then Windows Media Player's "AlbumArt*"
 * (the large one before the small one). Nothing else in the folder is taken: an image of another
 * kind (a scan of the back, a photo) would be shown as the album's cover.
 */
object FolderCover {

    /** Larger than this is not read: such a picture is no cover. */
    const val MAX_BYTES = 5L * 1024 * 1024

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val NAMES = listOf("cover", "folder", "front")

    /** @return the chosen one of [fileNames] (names only, no paths), or null */
    fun pick(fileNames: Collection<String>): String? {
        val images = fileNames.filter { extensionOf(it) in EXTENSIONS }
        for (name in NAMES) {
            images.firstOrNull { baseOf(it) == name }?.let { return it }
        }
        val albumArt = images.filter { baseOf(it).startsWith("albumart") }
        return albumArt.firstOrNull { "large" in baseOf(it) }
            ?: albumArt.firstOrNull { "small" !in baseOf(it) }
            ?: albumArt.firstOrNull()
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun baseOf(name: String): String =
        name.substringBeforeLast('.').lowercase(Locale.ROOT)
}
