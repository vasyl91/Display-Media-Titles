package vasyl.titles

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test
import vasyl.titles.PlaybackStates.Kind

class PlaybackStatesTest {

    @Test
    fun onlyPlayingPlays() {
        assertEquals(Kind.PLAYING, PlaybackStates.classify(PlaybackState.STATE_PLAYING))
    }

    @Test
    fun anErrorOrNoStateMeansNothingPlays() {
        for (state in listOf(
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR, PlaybackState.STATE_NONE,
        )) {
            assertEquals("state $state", Kind.STOPPED, PlaybackStates.classify(state))
        }
    }

    @Test
    fun bufferingSkippingAndSeekingAreOnTheWay() {
        for (state in listOf(
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
        )) {
            assertEquals("state $state", Kind.TRANSITIONAL, PlaybackStates.classify(state))
        }
    }

    @Test
    fun noStateOrAnUnknownOneChangesNothing() {
        assertEquals(Kind.UNKNOWN, PlaybackStates.classify(null))
        assertEquals(Kind.UNKNOWN, PlaybackStates.classify(99))
    }
}
