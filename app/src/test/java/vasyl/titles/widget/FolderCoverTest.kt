package vasyl.titles.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderCoverTest {

    @Test
    fun coverComesFirstThenFolderThenFront() {
        assertEquals("Cover.JPG", FolderCover.pick(listOf("front.png", "folder.jpg", "Cover.JPG", "01.mp3")))
        assertEquals("folder.jpg", FolderCover.pick(listOf("front.png", "folder.jpg", "01.mp3")))
        assertEquals("front.png", FolderCover.pick(listOf("front.png", "back.jpg")))
    }

    @Test
    fun theLargeAlbumArtBeforeTheSmallOne() {
        assertEquals(
            "AlbumArt_{X}_Large.jpg",
            FolderCover.pick(listOf("AlbumArtSmall.jpg", "AlbumArt_{X}_Large.jpg")),
        )
        assertEquals("AlbumArtSmall.jpg", FolderCover.pick(listOf("AlbumArtSmall.jpg", "track.flac")))
    }

    @Test
    fun noOtherPictureAndNoOtherKindOfFile() {
        assertNull(FolderCover.pick(listOf("back.jpg", "scan1.png", "booklet.pdf")))
        assertNull(FolderCover.pick(listOf("cover.txt", "folder.mp3")))
        assertNull(FolderCover.pick(emptyList()))
    }
}
