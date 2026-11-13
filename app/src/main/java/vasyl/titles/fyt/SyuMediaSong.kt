package vasyl.titles.fyt

/** The reads of android.os.Parcel that [SyuMediaSong.read] needs, so it can be tested on the JVM. */
interface ParcelReader {
    fun readInt(): Int
    fun readString(): String?
    fun dataAvail(): Int
}

/**
 * A MediaSource of com.syu.media as getPlayingSong() sends it, written after a "non-null" int:
 * ```
 *   int id, String title, displayName, artist, album, path, folder,
 *   int duration (ms), byte favorite (an int in the parcel), int length
 * ```
 * A RadioSource (internet radio) adds type, country, local, freqTitle and icon, which are not
 * needed here and are left unread.
 *
 * Rule: the title is the tag title, and the display name (the file name as the player shows it)
 * when the tag is empty. Texts are never null.
 */
class SyuMediaSong(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val durationMs: Long,
) {
    /**
     * What getPlayingSong() answers without a track: id -1 and every text "unknown". It is no
     * track, whatever its play state says.
     */
    val isPlaceholder: Boolean
        get() = id < 0 || path == UNKNOWN || path.isEmpty()

    fun isSameTrack(other: SyuMediaSong?): Boolean = other != null && id == other.id && path == other.path

    companion object {
        private const val UNKNOWN = "unknown"

        /** Reads a MediaSource with its "non-null" int in front; null if the player sent none. */
        fun read(p: ParcelReader): SyuMediaSong? {
            if (p.readInt() == 0) return null
            val id = p.readInt()
            val title = p.readString()
            val displayName = p.readString()
            val artist = p.readString()
            val album = p.readString()
            val path = p.readString()
            p.readString() // folder
            val duration = p.readInt()
            // favorite and length follow; a RadioSource has more after them. None of it is read.
            return SyuMediaSong(
                id = id,
                title = (title?.takeIf { it.isNotEmpty() } ?: displayName).orEmpty(),
                artist = artist.orEmpty(),
                album = album.orEmpty(),
                path = path.orEmpty(),
                durationMs = duration.coerceAtLeast(0).toLong(),
            )
        }
    }
}
