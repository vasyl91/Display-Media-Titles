package vasyl.titles.fyt

/**
 * The binder protocol of com.syu.media, the stock player of the FYT 7870, as read from its
 * decompiled IPlayer.Stub.onTransact and ICallback (the FYT Launcher Mod speaks the same protocol
 * in com.fyt.car.SyuMediaClient). There are no AIDL files: the transaction codes are fixed by the
 * player.
 *
 * Both playback services are exported without a permission and answer the same IPlayer:
 * ```
 *   music   action com.syu.media.music.MusicService          (local / USB files)
 *   online  action com.syu.media.online.OnlineRadioService   (internet radio)
 * ```
 * This app only reads: it never calls play, pause, next or previous.
 */
object SyuMediaProtocol {

    const val PACKAGE = "com.syu.media"
    const val ACTION_MUSIC = "com.syu.media.music.MusicService"
    const val ACTION_ONLINE = "com.syu.media.online.OnlineRadioService"

    const val IPLAYER = "com.syu.media.IPlayer"
    const val ICALLBACK = "com.syu.media.ICallback"

    /** isPlaying() -> int 0 / 1. */
    const val TX_IS_PLAYING = 8

    /** getProgress() -> int, milliseconds. */
    const val TX_GET_PROGRESS = 9

    /** getPlayingSong() -> int 0 / 1, then a MediaSource (see [SyuMediaSong.read]). */
    const val TX_GET_PLAYING_SONG = 10

    /** registerCallback(ICallback): the player keeps them in a RemoteCallbackList. */
    const val TX_REGISTER_CALLBACK = 15

    /** unregisterCallback(ICallback). */
    const val TX_UNREGISTER_CALLBACK = 16

    /** ICallback.onSongChanged(MediaSource). */
    const val CB_SONG_CHANGED = 1

    /** ICallback.onPlayStatusChanged(boolean), an int 0 / 1 on the wire. */
    const val CB_PLAY_STATUS_CHANGED = 2

    /** ICallback.onPlayModeChanged(int): repeat / shuffle, of no interest here. */
    const val CB_PLAY_MODE_CHANGED = 3
}
