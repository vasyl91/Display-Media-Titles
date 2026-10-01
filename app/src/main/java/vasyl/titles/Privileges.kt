package vasyl.titles

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Runtime detection of what the current build is allowed to do, plus permission checks shared by
 * [MainActivity] and [NotificationListener].
 *
 * The system flavors run as android.uid.system, the "phone" flavor is a regular app. Instead of
 * relying only on the flavor (BuildConfig.SYSTEM_BUILD) the checks look at the real process UID, so
 * a system flavor that was installed without the platform signature still degrades gracefully.
 */
object Privileges {

    private const val TAG = "Privileges"
    private const val INTERNAL_SYSTEM_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW"
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    private const val DEFAULT_MAX_OBSCURING_OPACITY = 0.8f

    /** UIDs are "userId * 100000 + appId" (UserHandle.PER_USER_RANGE, a hidden constant). */
    private const val PER_USER_RANGE = 100_000

    /** True when the process runs with the system UID (android:sharedUserId="android.uid.system"). */
    val isSystemUid: Boolean by lazy { Process.myUid() % PER_USER_RANGE == Process.SYSTEM_UID }

    /** True when windows above the status bar (TYPE_SYSTEM_ERROR) may be added. */
    fun canUseSystemOverlay(context: Context): Boolean =
        isSystemUid || context.checkSelfPermission(INTERNAL_SYSTEM_WINDOW) == PackageManager.PERMISSION_GRANTED

    /** Window type for the titles overlay. */
    @Suppress("DEPRECATION")
    fun overlayWindowType(context: Context): Int =
        if (canUseSystemOverlay(context)) {
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    /**
     * Makes a non-touchable overlay transparent for touches on Android 12+.
     *
     * Since Android 12 touches that pass through an untrusted overlay are blocked when the overlay is
     * more opaque than InputManager.maximumObscuringOpacityForTouch (0.8 by default). A privileged
     * window can instead be flagged as trusted overlay (hidden API, reachable for the system UID); if
     * that is not possible the window alpha is capped.
     */
    fun applyTouchPassThrough(context: Context, params: WindowManager.LayoutParams, privileged: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (privileged && markTrustedOverlay(params)) return
        val maxOpacity = runCatching {
            context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch
        }.getOrNull() ?: DEFAULT_MAX_OBSCURING_OPACITY
        params.alpha = minOf(params.alpha, maxOpacity)
    }

    private fun markTrustedOverlay(params: WindowManager.LayoutParams): Boolean = runCatching {
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(params)
        true
    }.getOrElse {
        Log.w(TAG, "setTrustedOverlay() not available: ${it.message}")
        false
    }

    // ---------------------------------------------------------------------------------------------
    // Permission state
    // ---------------------------------------------------------------------------------------------

    fun notificationListenerComponent(context: Context): ComponentName =
        ComponentName(context, NotificationListener::class.java)

    /**
     * Exact check whether our NotificationListener is enabled. The previous check
     * (`enabled_notification_listeners.contains(packageName)`) returned true for "vasyl.titles" when
     * only "vasyl.titles.phone" was enabled and crashed when the setting was null.
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val granted = runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(notificationListenerComponent(context))
            }.getOrNull()
            if (granted != null) return granted
        }
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /** Everything the settings screen asks for; used by MainActivity and WakeActivity. */
    fun allRequiredPermissionsGranted(context: Context, storageRequired: Boolean): Boolean =
        isIgnoringBatteryOptimizations(context) &&
            canDrawOverlays(context) &&
            isGranted(context, Manifest.permission.READ_PHONE_STATE) &&
            isGranted(context, Manifest.permission.READ_CALL_LOG) &&
            isNotificationListenerEnabled(context) &&
            (!storageRequired || isStorageAccessGranted(context))

    fun canDrawOverlays(context: Context): Boolean =
        canUseSystemOverlay(context) || Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull() ?: false

    /** Runtime storage permissions needed on this Android version (empty on Android 11+). */
    fun legacyStoragePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            emptyArray()
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    fun isStorageAccessGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            legacyStoragePermissions().all { isGranted(context, it) }
        }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------------------------------------
    // Self-granting (system UID only)
    // ---------------------------------------------------------------------------------------------

    /**
     * Enables our NotificationListener without user interaction. Only works for the system UID
     * (it passes every permission check and is exempt from hidden API restrictions).
     * @return true if the listener is enabled afterwards.
     */
    fun tryGrantNotificationListenerAccess(context: Context): Boolean {
        if (isNotificationListenerEnabled(context)) return true
        if (!isSystemUid) return false
        val component = notificationListenerComponent(context)

        val grantedViaApi = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            NotificationManager::class.java
                .getMethod(
                    "setNotificationListenerAccessGranted",
                    ComponentName::class.java,
                    Boolean::class.javaPrimitiveType
                )
                .invoke(nm, component, true)
            true
        }.getOrElse {
            Log.w(TAG, "setNotificationListenerAccessGranted failed: ${it.message}")
            false
        }

        if (!grantedViaApi) {
            // Android 8.0 has no such method: write the secure setting directly (WRITE_SECURE_SETTINGS).
            runCatching {
                val resolver = context.contentResolver
                val flat = component.flattenToString()
                val current = Settings.Secure.getString(resolver, ENABLED_NOTIFICATION_LISTENERS).orEmpty()
                if (current.split(':').none { it == flat }) {
                    val updated = if (current.isBlank()) flat else "$current:$flat"
                    Settings.Secure.putString(resolver, ENABLED_NOTIFICATION_LISTENERS, updated)
                }
            }.onFailure { Log.w(TAG, "Writing $ENABLED_NOTIFICATION_LISTENERS failed", it) }
        }
        return isNotificationListenerEnabled(context)
    }

    /**
     * Adds the app to the Doze power-save whitelist without user interaction (system UID only).
     * @return true if the app is exempt from battery optimizations afterwards.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun tryWhitelistFromBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true
        if (!isSystemUid) return false
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as IBinder
            val controller = Class.forName("android.os.IDeviceIdleController\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            controller.javaClass
                .getMethod("addPowerSaveWhitelistApp", String::class.java)
                .invoke(controller, context.packageName)
        }.onFailure { Log.w(TAG, "Power-save whitelist failed: ${it.message}") }
        return isIgnoringBatteryOptimizations(context)
    }
}
