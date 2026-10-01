package vasyl.titles.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import vasyl.titles.AppWaker

/**
 * Wakes the app after boot, always and invisibly: this also repairs the notification listener
 * binding if the system did not connect it (see ListenerGuard). The receiver already runs in the
 * app process, so no activity is needed and nothing comes to the front.
 *
 * The "autostart" setting only decides whether the settings screen opens at boot (when "Display UI"
 * is checked or a permission is missing).
 */
class Autostart : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Keeps the process alive until the listener check is done (a few seconds).
        val pending = goAsync()
        AppWaker.wake(context) { pending.finish() }

        val autostart = context.getSharedPreferences("savedPrefs", Context.MODE_PRIVATE)
            .getBoolean("autostart", false)
        // Allowed for the system UID and for apps holding "display over other apps"; otherwise
        // Android 10+ blocks activity starts from the background (caught in openSettings()).
        if (autostart && AppWaker.shouldShowSettings(context)) AppWaker.openSettings(context)
    }
}
