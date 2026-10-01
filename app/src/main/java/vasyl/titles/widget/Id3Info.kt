package vasyl.titles.widget

/**
 * Tag data of an audio file. A data class with a ByteArray needs content based equals/hashCode
 * (the generated ones compared the array by reference).
 */
data class Id3Info(
    var dataPic: ByteArray? = null,
    var strTitle: String = "",
    var strArtist: String = "",
    var strAlbum: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Id3Info) return false
        return strTitle == other.strTitle &&
            strArtist == other.strArtist &&
            strAlbum == other.strAlbum &&
            dataPic.contentEquals(other.dataPic)
    }

    override fun hashCode(): Int {
        var result = dataPic?.contentHashCode() ?: 0
        result = 31 * result + strTitle.hashCode()
        result = 31 * result + strArtist.hashCode()
        result = 31 * result + strAlbum.hashCode()
        return result
    }

    override fun toString(): String =
        "Id3Info(strTitle=$strTitle, strArtist=$strArtist, strAlbum=$strAlbum, dataPic=${dataPic?.size ?: 0} bytes)"
}
