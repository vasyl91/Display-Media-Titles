package vasyl.titles

import android.content.Context
import android.content.Intent
import android.util.Log
import vasyl.titles.receivers.PhoneListener

/**
 * Wakes the whole app without showing anything. Used by WakeActivity (launcher icon, FYT
 * autostart), WakeReceiver (other apps, e.g. a launcher; no activity at all) and Autostart (boot).
 */
object AppWaker {

    private const val TAG = "AppWaker"
    private const val PREFS = "savedPrefs"
    private const val KEY_DISPLAY_UI = "UI"
    private const val LISTENER_CHECK_DELAY_MS = 3_000L

    /**
     * Makes sure the notification listener is enabled and connected and the call state is tracked.
     * Idempotent; call it on the main thread. [onDone] runs after the listener check (a few
     * seconds later), e.g. to finish a BroadcastReceiver's goAsync().
     */
    fun wake(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        // With the system UID this grants notification access if it is missing (no-op otherwise).
        Privileges.tryGrantNotificationListenerAccess(app)
        PhoneListener.register(app)
        // Rebinds the listener if the system did not connect it (e.g. after a reboot). This replaces
        // requestRebind(), which only undoes requestUnbind() and therefore changed nothing here.
        ListenerGuard.check(app, LISTENER_CHECK_DELAY_MS, onDone)
    }

    /** The "Display UI" setting: whether starting the app shows the settings screen. */
    fun isDisplayUiEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISPLAY_UI, true)

    /**
     * For the launcher icon and boot: the settings screen is shown when "Display UI" is checked or
     * a required permission is missing (so that it can be granted).
     */
    fun shouldShowSettings(context: Context): Boolean =
        isDisplayUiEnabled(context) || !Privileges.allRequiredPermissionsGranted(context, BuildConfig.SYSTEM_BUILD)

    /** Opens the settings screen; an open one is brought to the front instead of being stacked. */
    fun openSettings(context: Context) {
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
        } catch (e: RuntimeException) { // background start restrictions, ActivityNotFoundException
            Log.w(TAG, "Cannot open the settings screen", e)
        }
    }
}
