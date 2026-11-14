package vasyl.titles.privileged

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import vasyl.titles.Privileges

/**
 * What the system flavors grant themselves with the system UID, granted to the standard flavor
 * through Shizuku or root (see [PrivilegedShell]): WRITE_SECURE_SETTINGS, notification access, the
 * battery exemption, display over other apps and all files access.
 *
 * Rules:
 * - Only with "Use Shizuku / root" on, and only what is still missing.
 * - Notification access goes through "cmd notification allow_listener", which also passes
 *   Android 13's "restricted setting" of sideloaded apps; where that command does not exist the
 *   app writes the secure setting itself with WRITE_SECURE_SETTINGS.
 * - All files access is never asked for in the permission flow of the standard flavor; with it,
 *   the tags and covers of local music files can be read.
 */
object PrivilegedGrants {

    private const val TAG = "PrivilegedShell"
    private val main = Handler(Looper.getMainLooper())

    /** Grants what is missing; [onDone] runs on the main thread afterwards. Any thread. */
    fun grantMissing(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!PrivilegedShell.isEnabled(app)) {
            onDone?.let { main.post(it) }
            return
        }
        PrivilegedShell.execute {
            try {
                grantMissingBlocking(app)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Granting the permissions failed", e)
            } finally {
                onDone?.let { main.post(it) }
            }
        }
    }

    private fun grantMissingBlocking(app: Context) {
        val missing = buildList {
            if (!Privileges.canWriteSecureSettings(app)) add(ShellOp.GRANT_WRITE_SECURE_SETTINGS)
            if (!Privileges.isNotificationListenerEnabled(app)) add(ShellOp.ALLOW_NOTIFICATION_LISTENER)
            if (!Privileges.isIgnoringBatteryOptimizations(app)) add(ShellOp.BATTERY_WHITELIST)
            if (!Settings.canDrawOverlays(app)) add(ShellOp.ALLOW_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                add(ShellOp.ALLOW_STORAGE)
            }
        }
        for (op in missing) {
            val result = PrivilegedShell.runBlocking(app, op)
            if (result == PrivilegedShell.Result.UNAVAILABLE) return // neither Shizuku nor root: stop asking
        }
        if (!Privileges.isNotificationListenerEnabled(app) && Privileges.canWriteSecureSettings(app)) {
            Privileges.addListenerToSecureSetting(app)
        }
    }
}
