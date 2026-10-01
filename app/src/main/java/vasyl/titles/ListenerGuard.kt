package vasyl.titles

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Makes sure the notification listener is really connected, and repairs the binding if it is not.
 *
 * NotificationManagerService binds enabled listeners at boot, but it retries a failed or died
 * binding only once ("not rebinding ... as a previous rebind attempt was made"). If the process was
 * killed or not allowed to start at that moment (e.g. by the vendor power manager right after boot),
 * the listener stays unbound until the package changes. That is why only a reinstall helped: starting
 * the app did not, and NotificationListenerService.requestRebind() does not either (it only undoes
 * requestUnbind()).
 *
 * Toggling the enabled state of the listener component is a package change for the system, so it
 * binds the listener again - exactly what the reinstall did - without killing the app.
 */
object ListenerGuard {

    private const val TAG = "ListenerGuard"

    /** Repairs are not repeated faster than this (no loop if the device keeps blocking the app). */
    private const val MIN_REPAIR_INTERVAL_MS = 60_000L

    /** The package-changed broadcast of a DONT_KILL_APP component change can be delayed by up to 10 s. */
    private const val VERIFY_DELAY_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())

    /** elapsedRealtime of the last repair; main thread only. */
    private var lastRepairAt = -MIN_REPAIR_INTERVAL_MS

    /**
     * Checks after [delayMs] whether the listener is connected and repairs the binding if it is not.
     * [onDone] runs after the check, e.g. to finish a BroadcastReceiver's goAsync(). Main thread.
     */
    fun check(context: Context, delayMs: Long, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        handler.postDelayed({
            try {
                if (!NotificationListener.isConnected) repair(app)
            } finally {
                onDone?.invoke()
            }
        }, delayMs)
    }

    private fun repair(context: Context) {
        // Without notification access there is nothing to bind (the settings screen asks for it).
        if (!Privileges.isNotificationListenerEnabled(context)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepairAt < MIN_REPAIR_INTERVAL_MS) return
        lastRepairAt = now

        Log.w(TAG, "Notification listener is not connected - asking the system to bind it again")
        val component = Privileges.notificationListenerComponent(context)
        try {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            // DEFAULT = the manifest state (enabled). Both changes reach the system as one package change.
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot toggle the listener component", e)
            return
        }
        handler.postDelayed({
            if (NotificationListener.isConnected) {
                Log.i(TAG, "Notification listener is connected again")
            } else {
                Log.w(
                    TAG,
                    "Notification listener is still not connected - the device may block starting " +
                        "the app in the background (autostart / background restrictions)"
                )
            }
        }, VERIFY_DELAY_MS)
    }
}
