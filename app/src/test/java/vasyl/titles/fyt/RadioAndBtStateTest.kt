package vasyl.titles.fyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAndBtStateTest {

    @Test
    fun frequenciesAreWrittenAsTheRadioShowsThem() {
        assertEquals("FM 101.50", StockFreq.title(10150))
        assertEquals("FM 87.50", StockFreq.title(8750))
        assertEquals("AM 1008", StockFreq.title(1008))
        assertEquals("FM 101.50 MHz", StockFreq.titleWithUnit(10150))
        assertEquals("AM 1008 kHz", StockFreq.titleWithUnit(1008))
    }

    @Test
    fun aStationWithoutANameIsItsFrequency() {
        val radio = RadioState().withFreq(10150)
        assertEquals("FM 101.50", radio.title)
        assertEquals("", radio.artist)
    }

    @Test
    fun aNamedStationShowsItsNameAndFrequency() {
        val radio = RadioState().withFreq(10150).withPs(" RMF FM ")
        assertEquals("RMF FM", radio.title)
        assertEquals("FM 101.50 MHz", radio.artist)
    }

    @Test
    fun anotherFrequencyDropsTheOldName() {
        val radio = RadioState().withFreq(10150).withPs("RMF FM").withFreq(9800)
        assertEquals("FM 98.00", radio.title)
        assertEquals(radio, radio.withFreq(9800))
    }

    @Test
    fun withoutAFrequencyThereIsNothingToShow() {
        assertNull(RadioState().withPs("RMF FM").title)
    }

    @Test
    fun theRadioPlaysOnlyOnItsChannel() {
        val radio = RadioState(10150)
        assertTrue(radio.isPlaying(MsProtocol.CH_RADIO))
        assertFalse(radio.isPlaying(MsProtocol.CH_THIRD_PARTY))
        assertFalse(radio.isPlaying(MsProtocol.CH_MUSIC))
    }

    @Test
    fun btPlaysOnlyWhenThePhonePlaysOnTheBtChannel() {
        val bt = BtState("Song", "Artist", phonePlaying = true)
        assertTrue(bt.isPlaying(MsProtocol.CH_BT))
        assertFalse(bt.isPlaying(MsProtocol.CH_RADIO))
        assertFalse(bt.copy(phonePlaying = false).isPlaying(MsProtocol.CH_BT))
    }

    @Test
    fun btWithoutATitleHasNothingToShow() {
        assertNull(BtState("", "Artist").shownTitle)
        assertNull(BtState("unknown").shownTitle)
        assertEquals("", BtState("Song", "Unknown").shownArtist)
    }

    @Test
    fun anotherSongHasAnotherKey() {
        assertEquals(BtState("A", "B").key, BtState("A", "B", phonePlaying = true).key)
        assertFalse(BtState("A", "B").key == BtState("A", "C").key)
    }
}
