package vasyl.titles

import android.media.session.PlaybackState

/**
 * Decides what the titles do with the FYT player (com.syu.music, a launcher's relay, the 7870's
 * stock sources) next to the media sessions. Free of android.* apart from PlaybackState's int
 * constants, and unit tested; [NotificationListener] carries out the [Action]s in their order.
 *
 * Rules:
 * - Only what plays is shown: "not playing" from the FYT player takes its title off, and so does a
 *   track the titles cannot resolve when it does not play, or a removal. The session's title stays
 *   when the session plays.
 * - The FYT player has priority when it STARTS playing: the session that plays is paused once
 *   ([Action.PAUSE_OTHER]). Not for the 7870's stock sources (only read; com.syu.media handles
 *   the audio focus itself), and not within the window after a session took the screen
 *   ([fytAllowed] false), where the FYT player's late "playing" is dropped altogether.
 * - An update that brings another track and "paused" together (the end of a playlist) still takes
 *   the title on screen off.
 * - A com.syu.music title put on screen is checked against its sound ([Action.CHECK_SILENCE]).
 * - A session that stops takes the title off unless the FYT player plays.
 */
class TitleArbiter {

    enum class Action {
        /** Pause the session that plays (the FYT player started). */
        PAUSE_OTHER,

        /** Take the title on screen off. */
        HIDE,

        /** Show the FYT player's track. */
        SHOW_FYT,

        /** The widgets show "playing". */
        WIDGET_PLAYING,

        /** The widgets show "paused". */
        WIDGET_PAUSED,

        /** Check com.syu.music against its sound (SyuMusicSilence). */
        CHECK_SILENCE,
    }

    /** The FYT player reports "playing". */
    var fytPlaying = false
        private set

    /** The FYT player's current track is applied (on screen, or given way to a session). */
    var fytShown = false
        private set

    /** False within the window after a session took the screen: FYT updates are not applied. */
    var fytAllowed = true
        private set

    /** "Playing" arrived after "not playing": handled once the track is resolved. */
    private var startPending = false

    /** A new connection: nothing is known of the FYT player. */
    fun reset() {
        fytPlaying = false
        startPending = false
        fytShown = false
        fytAllowed = true
    }

    /** The last FYT update is applied again (after a reconnect, a call): it is shown anew. */
    fun forgetShown() {
        fytShown = false
    }

    /** An FYT update arrived, before its track is resolved. */
    fun onFytUpdate(playing: Boolean) {
        if (playing && !fytPlaying) startPending = true
        fytPlaying = playing
        if (!playing) startPending = false
    }

    /**
     * The FYT update's track is resolved.
     *
     * @param sameTrackShown the title on screen is this track's (its title or file name)
     * @param is7870Stock the update came from a 7870 stock source
     * @param controllerState the attached session's play state, null without one
     */
    fun onFytResolved(sameTrackShown: Boolean, is7870Stock: Boolean, controllerState: Int?): List<Action> {
        val actions = ArrayList<Action>(5)
        if (startPending && fytPlaying) {
            startPending = false
            if (fytAllowed && !is7870Stock) actions += Action.PAUSE_OTHER
        }
        val wasShown = fytShown
        if (!sameTrackShown) fytShown = false

        if (fytPlaying && !fytShown && fytAllowed) {
            fytShown = true
            if (controllerState == PlaybackState.STATE_PLAYING || controllerState == PlaybackState.STATE_STOPPED) {
                actions += Action.HIDE
            }
            actions += Action.WIDGET_PLAYING
            actions += Action.SHOW_FYT
            if (!is7870Stock) actions += Action.CHECK_SILENCE
        }
        if (!fytPlaying && (fytShown || wasShown)) {
            fytShown = false
            if (controllerState != PlaybackState.STATE_PLAYING) {
                actions += Action.HIDE
                actions += Action.WIDGET_PAUSED
            }
        }
        return actions
    }

    /** "Not playing" whose track cannot be resolved (no path, the file is gone). */
    fun onFytUnresolvedStop(controllerPlaying: Boolean): List<Action> {
        if (!fytShown) return emptyList()
        fytShown = false
        return if (controllerPlaying) emptyList() else listOf(Action.HIDE, Action.WIDGET_PAUSED)
    }

    /** The FYT source went away. */
    fun onFytRemove(controllerPlaying: Boolean): List<Action> {
        fytPlaying = false
        startPending = false
        fytShown = false
        return if (controllerPlaying) emptyList() else listOf(Action.HIDE, Action.WIDGET_PAUSED)
    }

    /** A session's track was put on screen: the FYT player gives way. */
    fun onControllerShown() {
        fytPlaying = false
        fytShown = true
    }

    /** A session took the screen: FYT updates wait until [allowFyt]. */
    fun blockFyt() {
        fytAllowed = false
    }

    fun allowFyt() {
        fytAllowed = true
    }

    /** The session stopped, went away or was replaced. */
    fun onControllerStopped(): List<Action> =
        if (fytPlaying) emptyList() else listOf(Action.HIDE, Action.WIDGET_PAUSED)
}
