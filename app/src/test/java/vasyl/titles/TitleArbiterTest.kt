package vasyl.titles

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vasyl.titles.TitleArbiter.Action

/** Characterises the decisions NotificationListener made before they moved here. */
class TitleArbiterTest {

    private val playing = PlaybackState.STATE_PLAYING
    private val paused = PlaybackState.STATE_PAUSED

    private fun TitleArbiter.update(play: Boolean, same: Boolean, is7870: Boolean = false, controller: Int? = null): List<Action> {
        onFytUpdate(play)
        return onFytResolved(same, is7870, controller)
    }

    @Test
    fun anFytStartPausesThePlayingSessionAndShowsItsTrack() {
        val a = TitleArbiter()
        assertEquals(
            listOf(Action.PAUSE_OTHER, Action.HIDE, Action.WIDGET_PLAYING, Action.SHOW_FYT, Action.CHECK_SILENCE),
            a.update(play = true, same = false, controller = playing),
        )
        assertTrue(a.fytPlaying)
        assertTrue(a.fytShown)
    }

    @Test
    fun theSameTrackPlayingOnChangesNothing() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        assertEquals(emptyList<Action>(), a.update(play = true, same = true))
    }

    @Test
    fun aPauseTakesTheTitleOff() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        assertEquals(listOf(Action.HIDE, Action.WIDGET_PAUSED), a.update(play = false, same = true))
        assertFalse(a.fytShown)
    }

    @Test
    fun aPauseLeavesAPlayingSessionsTitleAlone() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        assertEquals(emptyList<Action>(), a.update(play = false, same = true, controller = playing))
    }

    @Test
    fun aNewTrackThatArrivesPausedStillTakesTheTitleOff() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        assertEquals(listOf(Action.HIDE, Action.WIDGET_PAUSED), a.update(play = false, same = false))
    }

    @Test
    fun withinTheWindowAfterASessionALateFytPlayingIsDroppedAndPausesNothing() {
        val a = TitleArbiter()
        a.onControllerShown()
        a.blockFyt()
        assertEquals(emptyList<Action>(), a.update(play = true, same = false, controller = playing))
        a.allowFyt()
        // Its next "playing" shows it, but the start was used up: the session is not paused.
        assertEquals(
            listOf(Action.HIDE, Action.WIDGET_PLAYING, Action.SHOW_FYT, Action.CHECK_SILENCE),
            a.update(play = true, same = false, controller = playing),
        )
    }

    @Test
    fun the7870StockSourcesPauseNothingAndAreNotCheckedAgainstSound() {
        val a = TitleArbiter()
        assertEquals(
            listOf(Action.HIDE, Action.WIDGET_PLAYING, Action.SHOW_FYT),
            a.update(play = true, same = false, is7870 = true, controller = playing),
        )
    }

    @Test
    fun aRemovalTakesTheTitleOffAndForgetsThePlayer() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        assertEquals(listOf(Action.HIDE, Action.WIDGET_PAUSED), a.onFytRemove(controllerPlaying = false))
        assertFalse(a.fytPlaying)
        assertFalse(a.fytShown)
        a.update(play = true, same = false)
        assertEquals(emptyList<Action>(), a.onFytRemove(controllerPlaying = true))
    }

    @Test
    fun anUpdateShownAgainAfterAReconnectIsShownAndCheckedAgainstSound() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        a.reset()
        a.forgetShown()
        assertEquals(
            listOf(Action.PAUSE_OTHER, Action.WIDGET_PLAYING, Action.SHOW_FYT, Action.CHECK_SILENCE),
            a.update(play = true, same = true, controller = paused),
        )
    }

    @Test
    fun aStopThatCannotBeResolvedTakesOnlyAShownTitleOff() {
        val a = TitleArbiter()
        assertEquals(emptyList<Action>(), a.onFytUnresolvedStop(controllerPlaying = false))
        a.update(play = true, same = false)
        a.onFytUpdate(false)
        assertEquals(listOf(Action.HIDE, Action.WIDGET_PAUSED), a.onFytUnresolvedStop(controllerPlaying = false))
    }

    @Test
    fun aSessionOnScreenMakesTheFytPlayerGiveWay() {
        val a = TitleArbiter()
        a.update(play = true, same = false)
        a.onControllerShown()
        assertFalse(a.fytPlaying)
        assertTrue(a.fytShown)
        // The FYT player's "paused" for another track then leaves the playing session's title.
        assertEquals(emptyList<Action>(), a.update(play = false, same = false, controller = playing))
    }

    @Test
    fun aStoppedSessionTakesTheTitleOffUnlessTheFytPlayerPlays() {
        val a = TitleArbiter()
        assertEquals(listOf(Action.HIDE, Action.WIDGET_PAUSED), a.onControllerStopped())
        a.update(play = true, same = false)
        assertEquals(emptyList<Action>(), a.onControllerStopped())
    }
}
