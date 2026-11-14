package vasyl.titles

import org.junit.Assert.assertEquals
import org.junit.Test
import vasyl.titles.SyuMusicSilence.Verdict

class SyuMusicSilenceTest {

    private fun track(playing: Boolean = true, at: Long = 1_000L, origin: FytOrigin = FytOrigin.LAUNCHER_RELAY) =
        FytTrack(playing, "Title", "Artist", "", "/storage/usb/a.mp3", 200_000L, 0L, origin, at)

    @Test
    fun backInThePlayerWithoutARelayedStopIsAStop() {
        // Last relay at 1000, the sound paused at 1100, nothing since, no sound any more.
        assertEquals(Verdict.STOPPED, SyuMusicSilence.check(track(at = 1_000L), true, 1_100L, false))
    }

    @Test
    fun aPlayingReportAfterTheAudioChangeMeansItPlays() {
        // A new track (the player re-created its MediaPlayer) or another app's short sound.
        assertEquals(Verdict.PLAYING, SyuMusicSilence.check(track(at = 2_000L), true, 1_100L, false))
    }

    @Test
    fun anotherSoundOnlyDelaysTheVerdict() {
        assertEquals(Verdict.WAIT, SyuMusicSilence.check(track(at = 1_000L), true, 1_100L, true))
    }

    @Test
    fun nothingIsWatchedWhenTheFytPlayerIsNotTakenAsPlaying() {
        assertEquals(Verdict.NOT_WATCHED, SyuMusicSilence.check(track(at = 1_000L), false, 1_100L, false))
        assertEquals(Verdict.NOT_WATCHED, SyuMusicSilence.check(track(playing = false), true, 1_100L, false))
        assertEquals(Verdict.NOT_WATCHED, SyuMusicSilence.check(null, true, 1_100L, false))
    }

    @Test
    fun theDirectUpdatesOfComSyuMusicAreWatchedToo() {
        assertEquals(Verdict.STOPPED, SyuMusicSilence.check(track(origin = FytOrigin.SYU_MUSIC), true, 1_100L, false))
    }

    @Test
    fun the7870StockSourcesAreNeverWatched() {
        for (origin in FytOrigin.STOCK_7870) {
            assertEquals(origin.name, Verdict.NOT_WATCHED, SyuMusicSilence.check(track(origin = origin), true, 1_100L, false))
        }
    }
}
