package vasyl.titles.widget

import java.util.concurrent.atomic.AtomicInteger

/**
 * The play / pause symbol of the widgets around a press. Free of android.* and unit tested.
 *
 * Rules (as the FYT Launcher Mod's widget, LauncherMusicWidget):
 * - A press shows the opposite of the state at the press at once, before the player answers.
 * - [HOLD_MS] later the symbol is set to the player's real state, so a player that did not react
 *   (not running, or the command was lost) cannot leave a false symbol behind. A player that
 *   answered has set it already; setting it again changes nothing.
 * - Only the last press does this: a second press within the hold is not undone by the first.
 *
 * Thread safe: presses come from the widgets' action callbacks.
 */
class PlayPauseHold {

    private val generation = AtomicInteger()

    /** Registers a press; the token says later whether it is still the last one. */
    fun press(): Int = generation.incrementAndGet()

    fun isLatest(token: Int): Boolean = generation.get() == token

    companion object {
        const val HOLD_MS = 2_000L

        /** The symbol right after a press: "playing" when it did not play at the press. */
        fun symbolAfterPress(playingAtPress: Boolean): Boolean = !playingAtPress
    }
}
