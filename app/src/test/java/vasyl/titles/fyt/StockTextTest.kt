package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockTextTest {

    @Test
    fun placeholdersAreNoValue() {
        for (text in listOf(null, "", "  ", "null", "unknown", "Unknown", "UNKNOWN", "<unknown>")) {
            assertNull(text, StockText.validTag(text))
        }
    }

    @Test
    fun aValueIsTrimmed() {
        assertEquals("Song", StockText.validTag("  Song "))
        assertEquals("Unknown Pleasures", StockText.validTag("Unknown Pleasures"))
    }

    @Test
    fun onlyAnAbsolutePathIsALocalFile() {
        assertTrue(StockText.isLocalFile("/storage/usb/a.mp3"))
        assertFalse(StockText.isLocalFile("http://stream.example/one"))
        assertFalse(StockText.isLocalFile("//server/share"))
        assertFalse(StockText.isLocalFile("stock-radio:/10150"))
        assertFalse(StockText.isLocalFile("/"))
        assertFalse(StockText.isLocalFile(""))
        assertFalse(StockText.isLocalFile(null))
    }

    @Test
    fun theBaseNameDropsTheExtension() {
        assertEquals("Song", StockText.fileBaseName("/storage/usb/Song.mp3"))
        assertEquals("a.b", StockText.fileBaseName("/x/a.b.flac"))
        assertEquals("NoDot", StockText.fileBaseName("/x/NoDot"))
        assertEquals(".hidden", StockText.fileBaseName("/x/.hidden"))
        assertNull(StockText.fileBaseName("http://stream.example/one.mp3"))
    }
}
