package vasyl.titles.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import vasyl.titles.AppWaker

/**
 * Starts the app from other apps (e.g. a launcher) as defined by the "Display UI" setting:
 *  - checked: the settings screen opens like after tapping the icon (an open one is brought to the
 *    front instead of being stacked),
 *  - unchecked: the app only wakes in the background. No activity is started, so nothing comes to
 *    the front and a launcher is not paused.
 *
 * Usage (explicit broadcast; FLAG_INCLUDE_STOPPED_PACKAGES also reaches the app after a force stop):
 *
 *     sendBroadcast(
 *         Intent("vasyl.titles.action.WAKE")
 *             .setPackage("<package of the installed flavor>")
 *             .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
 *     )
 *
 * Unlike the launcher icon, a missing permission does not open the settings screen here: with
 * "Display UI" unchecked the app always stays in the background (the icon or the secret code open
 * the settings in that case). The receiver only triggers the idempotent wake and, at most, opens
 * the settings screen, so there is nothing to protect.
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKE) return
        AppWaker.wake(context)
        if (AppWaker.isDisplayUiEnabled(context)) {
            Log.d(TAG, "Woken by broadcast, opening the settings screen")
            // Allowed for the system UID. The "phone" flavor relies on "display over other apps"
            // (Android 15+ additionally requires a visible overlay window at that moment).
            AppWaker.openSettings(context)
        } else {
            Log.d(TAG, "Woken by broadcast, staying in the background")
        }
    }

    companion object {
        const val ACTION_WAKE = "vasyl.titles.action.WAKE"
        private const val TAG = "WakeReceiver"
    }
}
