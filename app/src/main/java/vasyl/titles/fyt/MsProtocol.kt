package vasyl.titles.fyt

/**
 * The binder protocol of com.syu.ms, FYT's MCU service, as far as this app reads it (the FYT
 * Launcher Mod uses the same hand-written interfaces in com.syu.ipc and com.syu.remote):
 * ```
 *   IRemoteToolkit   1 getRemoteModule(int module) -> IRemoteModule
 *   IRemoteModule    2 get(int code, int[], float[], String[]) -> int 0/1, int[], float[], String[]
 *                    3 register(IModuleCallback, int code, int update)   one-way
 *                    4 unregister(IModuleCallback, int code)             one-way
 *   IModuleCallback  1 update(int code, int[], float[], String[])       one-way
 * ```
 * The callback does not say which module an update is for, so every module gets a callback of
 * its own. On the FYT 7870 a module sends every update to every registered callback whatever code
 * it was registered for, so the codes are filtered on arrival.
 *
 * This app only reads: it never sends a cmd.
 */
object MsProtocol {

    const val PACKAGE = "com.syu.ms"
    const val ACTION = "com.syu.ms.toolkit"

    const val ITOOLKIT = "com.syu.ipc.IRemoteToolkit"
    const val IMODULE = "com.syu.ipc.IRemoteModule"
    const val ICALLBACK = "com.syu.ipc.IModuleCallback"

    const val TX_GET_REMOTE_MODULE = 1
    const val TX_GET = 2
    const val TX_REGISTER = 3
    const val TX_UNREGISTER = 4
    const val CB_UPDATE = 1

    const val MODULE_MAIN = 0
    const val MODULE_RADIO = 1
    const val MODULE_BT = 2

    /** Main module: the MCU's sound channel (app id), ints[0]. */
    const val MAIN_APP_ID = 0

    /** Radio module: frequency, ints[0] (FM in 10 kHz units, AM in kHz, see [StockFreq]). */
    const val RADIO_FREQ = 1

    /** Radio module: the station name (RDS PS), strs[0]. Code 13 is the PTY on the 7870, not text. */
    const val RADIO_PS = 26

    /** BT module: title, artist and album of the phone's song, strs[0]. */
    const val BT_TITLE = 0
    const val BT_ARTIST = 1
    const val BT_ALBUM = 26

    /** BT module: the phone's play state, ints[0] == 1 while it plays. */
    const val BT_PLAY_STATE = 13

    /** Sound channels (app ids) of the MCU, as the FYT 7870 uses them. */
    const val CH_NONE = 0
    const val CH_RADIO = 1
    const val CH_BT = 3
    const val CH_ONLINE = 7
    const val CH_MUSIC = 8
    const val CH_THIRD_PARTY = 10

    /** The codes registered per module. */
    val MAIN_CODES = intArrayOf(MAIN_APP_ID)
    val RADIO_CODES = intArrayOf(RADIO_FREQ, RADIO_PS)
    val BT_CODES = intArrayOf(BT_TITLE, BT_ARTIST, BT_PLAY_STATE, BT_ALBUM)
}
