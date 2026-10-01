@file:Suppress("DEPRECATION")

package vasyl.titles.receivers

import android.Manifest
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import vasyl.titles.NotificationListener
import vasyl.titles.Privileges

/**
 * Tracks the call state for the whole process ([CALLING]).
 *
 * Previously PhoneStateBroadcastReceiver registered a NEW listener on every PHONE_STATE broadcast and
 * on every notification listener (re)connect, and none was ever unregistered, so listeners piled up
 * and each of them refreshed the overlay after a call. Now exactly one listener is registered per
 * process ([register] is idempotent). On Android 12+ the non-deprecated TelephonyCallback is used.
 */
class PhoneListener : PhoneStateListener() {

    @Deprecated("Deprecated in Java")
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        onCallState(state)
    }

    companion object {
        private const val TAG = "PhoneListener"

        @Volatile
        @JvmField
        var CALLING: Boolean = false

        /** The registered PhoneListener / TelephonyCallback. Main thread only. */
        private var registration: Any? = null

        /**
         * Registers the call state listener once per process. Safe to call repeatedly, must be called
         * on the main thread. On Android 12+ it needs READ_PHONE_STATE; without it nothing happens and
         * a later call (e.g. after the permission was granted) registers it.
         */
        @JvmStatic
        fun register(context: Context) {
            if (registration != null) return
            val appContext = context.applicationContext
            val telephony = appContext.getSystemService(TelephonyManager::class.java) ?: return
            try {
                registration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (!Privileges.isGranted(appContext, Manifest.permission.READ_PHONE_STATE)) return
                    CallStateCallback().also { telephony.registerTelephonyCallback(appContext.mainExecutor, it) }
                } else {
                    // No permission is needed for the call state before Android 12.
                    PhoneListener().also { telephony.listen(it, PhoneStateListener.LISTEN_CALL_STATE) }
                }
            } catch (e: RuntimeException) { // SecurityException, or no telephony service on the device
                Log.w(TAG, "Cannot listen to the call state", e)
            }
        }

        /** Handles TelephonyManager.CALL_STATE_* values. */
        @JvmStatic
        fun onCallState(state: Int) {
            val wasCalling = CALLING
            CALLING = state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK
            // Call has ended: show the current track again. The initial IDLE callback that arrives
            // right after registering no longer triggers a refresh.
            if (wasCalling && !CALLING) NotificationListener.setDefaultStatusFromCompanion()
        }

        /** Handles TelephonyManager.EXTRA_STATE of the PHONE_STATE broadcast. */
        @JvmStatic
        fun onPhoneStateBroadcast(extraState: String?) {
            val state = when (extraState) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> return
            }
            onCallState(state)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onCallState(state)
        }
    }
}
