package vasyl.titles

/**
 * Notices that com.syu.music (7862 / 8581) stopped without saying so. Free of android.* and unit
 * tested.
 *
 * Closed with Back, com.syu.music pauses and sends one last update without a title. A launcher
 * relays only updates with a title (the FYT Launcher Mod's "titlesReceiver"), so on such a unit the
 * titles never hear "stopped": the last word they got is "playing", and the title stayed on screen
 * (capture 14-11-2026 13:05:54: the player's sound paused at 13:06:05.916, no relay after
 * 13:06:03.891). What is left is the sound itself: com.syu.music plays through Android's audio,
 * and while it plays it reports "playing" about once a second.
 *
 * Rules, checked a while after Android's audio changed:
 * - Only a com.syu.music track that the titles take as playing is watched. The FYT 7870's stock
 *   sources are not: the radio and BT sound is the MCU's, and com.syu.media reports its own state.
 * - A "playing" reported after the audio change means it plays: nothing is done.
 * - Otherwise, with no Android player making a sound any more, it stopped. As long as some sound
 *   is still heard (another app, a navigation prompt), it is only checked again; the next audio
 *   change starts the check over.
 */
internal object SyuMusicSilence {

    enum class Verdict {
        /** Nothing of com.syu.music is taken as playing. */
        NOT_WATCHED,

        /** It reported "playing" after the audio change. */
        PLAYING,

        /** Some sound is still heard: check again. */
        WAIT,

        /** No sound and no "playing" since the audio change: it stopped. */
        STOPPED,
    }

    /**
     * @param last the last FYT update ([FytPlayerBus.last])
     * @param fytPlaying whether the titles take the FYT player as playing
     * @param audioChangedAtMs when Android's audio changed, on the clock of [FytTrack.receivedAt]
     * @param musicActive whether an Android player makes a sound now
     */
    fun check(last: FytTrack?, fytPlaying: Boolean, audioChangedAtMs: Long, musicActive: Boolean): Verdict {
        if (!fytPlaying || last == null || !last.playing || last.origin.is7870Stock) return Verdict.NOT_WATCHED
        if (last.receivedAt > audioChangedAtMs) return Verdict.PLAYING
        return if (musicActive) Verdict.WAIT else Verdict.STOPPED
    }

    /** The origins whose track a silent stop removes. */
    val ORIGINS: Set<FytOrigin> = setOf(FytOrigin.SYU_MUSIC, FytOrigin.LAUNCHER_RELAY)
}
