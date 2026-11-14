package vasyl.titles

import android.media.session.PlaybackState

/**
 * What a media session's play state means for the titles. Only PlaybackState's int constants are
 * used (inlined at compile time), so this is unit tested on the JVM.
 *
 * Rules:
 * - Only PLAYING shows a title.
 * - PAUSED, STOPPED, ERROR and NONE mean nothing plays: the title goes at once. A player in error
 *   (a stream that lost its connection) used to keep its title on screen.
 * - BUFFERING, CONNECTING, SKIPPING_* and seeking (FAST_FORWARDING, REWINDING) are on the way
 *   between two tracks or positions.
 * - Without a state (null) nothing changes.
 */
object PlaybackStates {

    enum class Kind { PLAYING, TRANSITIONAL, STOPPED, UNKNOWN }

    fun classify(state: Int?): Kind = when (state) {
        null -> Kind.UNKNOWN
        PlaybackState.STATE_PLAYING -> Kind.PLAYING
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR,
        PlaybackState.STATE_NONE -> Kind.STOPPED
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> Kind.TRANSITIONAL
        else -> Kind.UNKNOWN
    }
}
