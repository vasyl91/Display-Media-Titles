package vasyl.titles.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vasyl.titles.privileged.ObscuringLimitPolicy.LimitAction
import vasyl.titles.privileged.ObscuringLimitPolicy.Route

class ObscuringLimitPolicyTest {

    @Test
    fun routes() {
        assertEquals(Route.TRUSTED, ObscuringLimitPolicy.route(34, true, true, true, 0.8f))
        assertEquals(Route.WRITE_DIRECT, ObscuringLimitPolicy.route(34, false, true, true, 0.8f))
        assertEquals(Route.HELPER, ObscuringLimitPolicy.route(34, false, false, true, 0.8f))
        assertEquals(Route.UNAVAILABLE, ObscuringLimitPolicy.route(34, false, false, false, 0.8f))
    }

    @Test
    fun notNeededBeforeAndroid12OrAtTheFullLimit() {
        assertEquals(Route.NOT_NEEDED, ObscuringLimitPolicy.route(30, false, true, true, 0.8f))
        assertEquals(Route.NOT_NEEDED, ObscuringLimitPolicy.route(34, false, true, true, 1f))
    }

    @Test
    fun raiseWhileShown() {
        assertEquals(LimitAction.RAISE, ObscuringLimitPolicy.limitAction(true, false, null))
        assertEquals(LimitAction.RAISE, ObscuringLimitPolicy.limitAction(true, false, "0.8"))
        assertEquals(LimitAction.RAISE, ObscuringLimitPolicy.limitAction(true, true, "0.5"))
        assertEquals(LimitAction.NOTHING, ObscuringLimitPolicy.limitAction(true, false, "1"))
        assertEquals(LimitAction.NOTHING, ObscuringLimitPolicy.limitAction(true, true, "1.0"))
    }

    @Test
    fun restoreWhenHiddenOnlyWhatWeRaised() {
        assertEquals(LimitAction.RESTORE, ObscuringLimitPolicy.limitAction(false, true, "1"))
        assertEquals(LimitAction.FORGET, ObscuringLimitPolicy.limitAction(false, true, "0.7"))
        for (value in listOf(null, "0.8", "1", "1.0", "abc")) {
            assertEquals(LimitAction.NOTHING, ObscuringLimitPolicy.limitAction(false, false, value))
        }
    }

    @Test
    fun parse() {
        assertEquals(0.8f, ObscuringLimitPolicy.parseLimit(null), 0f)
        assertEquals(0.8f, ObscuringLimitPolicy.parseLimit(""), 0f)
        assertEquals(0.8f, ObscuringLimitPolicy.parseLimit("abc"), 0f)
        assertEquals(1f, ObscuringLimitPolicy.parseLimit("1"), 0f)
        assertEquals(0.75f, ObscuringLimitPolicy.parseLimit(" 0.75 "), 0f)
    }

    @Test
    fun needsRaiseOnlyForAShownTitleBelowTheFullLimit() {
        assertTrue(ObscuringLimitPolicy.needsRaise(true, 0.8f))
        assertFalse(ObscuringLimitPolicy.needsRaise(true, 1f))
        assertFalse(ObscuringLimitPolicy.needsRaise(false, 0.8f))
    }

    @Test
    fun backWithinThreeSecondsRestoresNothing() {
        val d = HideDebounce()
        d.onHidden(0)
        d.onShown()
        assertFalse(d.due(3_000))
        assertFalse(d.due(10_000))
    }

    @Test
    fun restoresOnceThreeSecondsAfterTheLastHide() {
        val d = HideDebounce()
        d.onHidden(500)
        d.onShown()
        d.onHidden(2_000)
        assertFalse(d.due(4_999))
        assertTrue(d.due(5_000))
        assertFalse(d.due(5_001))
    }
}
