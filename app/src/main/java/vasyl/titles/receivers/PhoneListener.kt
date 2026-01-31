@file:Suppress("DEPRECATION")

package vasyl.titles

import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

@Suppress("DEPRECATION")
class PhoneListener : PhoneStateListener() {
    @Deprecated("Deprecated in Java")
    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        super.onCallStateChanged(state, incomingNumber)

        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                CALLING = false
                // Call has ended
                NotificationListener.setDefaultStatusFromCompanion()
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> CALLING = true
            TelephonyManager.CALL_STATE_RINGING -> CALLING = true
            else -> {}
        }
    }

    companion object {
        @JvmField
        var CALLING: Boolean = false
    }
}