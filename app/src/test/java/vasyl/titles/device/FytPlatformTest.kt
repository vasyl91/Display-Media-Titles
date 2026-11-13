package vasyl.titles.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vasyl.titles.device.FytPlatform.Chipset

class FytPlatformTest {

    @Test
    fun theCodeOfTheUnitTellsItsChipset() {
        assertEquals(Chipset.FYT_7862, FytPlatform.classify(true, "6315", "ums512_1h10_Natv"))
        assertEquals(Chipset.FYT_7870, FytPlatform.classify(true, "6318", "uis7870sc_2h10_nosec"))
        assertEquals(Chipset.FYT_8581, FytPlatform.classify(true, "6316"))
        assertEquals(Chipset.FYT_9853I, FytPlatform.classify(true, "6521"))
    }

    @Test
    fun withoutACodeTheDeviceNamesDo() {
        assertEquals(Chipset.FYT_7870, FytPlatform.classify(true, "", "uis7870sc_2h10_nosec"))
        assertEquals(Chipset.FYT_7862, FytPlatform.classify(true, "", "ums512_1h10_Natv"))
        assertEquals(Chipset.FYT_8581, FytPlatform.classify(true, "", "sp9863a_1h10"))
        assertEquals(Chipset.FYT_9853I, FytPlatform.classify(true, null, "sp9853i_1h10"))
    }

    @Test
    fun theNamesAreComparedWithoutCase() {
        assertEquals(Chipset.FYT_7862, FytPlatform.classify(true, "", "UMS512_1H10"))
    }

    @Test
    fun theCodeWinsOverTheNames() {
        assertEquals(Chipset.FYT_7862, FytPlatform.classify(true, "6315", "uis7870sc_2h10_nosec"))
    }

    @Test
    fun anFytUnitOfAnotherChipsetIsUnknown() {
        assertEquals(Chipset.UNKNOWN_FYT, FytPlatform.classify(true, "823", "x"))
        assertEquals(Chipset.UNKNOWN_FYT, FytPlatform.classify(true, "6312"))
        assertEquals(Chipset.UNKNOWN_FYT, FytPlatform.classify(true, "", null))
    }

    @Test
    fun aChipsetWithoutTheFytMarkIsNotAnFytPlatform() {
        // Teyes units and T618 tablets use the UMS512 too.
        assertEquals(Chipset.NOT_FYT, FytPlatform.classify(false, "", "ums512_1h10"))
        assertEquals(Chipset.NOT_FYT, FytPlatform.classify(false, "6315", "ums512_1h10"))
    }

    @Test
    fun eachMarkAloneMakesAnFytUnit() {
        assertTrue(FytPlatform.isFyt("fyt_board", "", "", "", ""))
        assertTrue(FytPlatform.isFyt("", "Fyt", "", "", ""))
        assertTrue(FytPlatform.isFyt("", "", "6315", "", ""))
        assertTrue(FytPlatform.isFyt("", "", "", "UIS7862.fyt.2021", ""))
        assertTrue(FytPlatform.isFyt("", "", "", "", "1234"))
    }

    @Test
    fun withoutAMarkItIsNotAnFytUnit() {
        assertFalse(FytPlatform.isFyt("", "", "", "", ""))
        assertFalse(FytPlatform.isFyt(null, null, null, null, null))
        assertFalse(FytPlatform.isFyt("ums512", "unisoc", " ", "TP1A", " "))
    }
}
