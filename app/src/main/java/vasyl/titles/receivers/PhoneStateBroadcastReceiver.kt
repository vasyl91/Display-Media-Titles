package vasyl.titles.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Receives android.intent.action.PHONE_STATE (protected broadcast, only the system can send it).
 *
 * The broadcast already carries the new state, so [PhoneListener.CALLING] is updated right away
 * instead of only registering yet another listener (see [PhoneListener.register]).
 */
class PhoneStateBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            PhoneListener.onPhoneStateBroadcast(intent.getStringExtra(TelephonyManager.EXTRA_STATE))
        }
        // Keeps call state updates coming while the process is alive (registered once per process).
        PhoneListener.register(context)
    }
}
