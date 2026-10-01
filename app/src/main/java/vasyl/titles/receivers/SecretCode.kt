package vasyl.titles.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.edit
import vasyl.titles.AppWaker

/**
 * Dialing *#*#3368#*#* re-enables and opens the settings UI.
 *
 * The receiver used to create a NotificationListener instance by hand (a service that is never
 * bound by the system) just to set an unused field on it; that is gone.
 */
class SecretCode : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != LEGACY_SECRET_CODE_ACTION && action != TelephonyManager.ACTION_SECRET_CODE) return

        // Android 10+ sends the code with both actions: handling both started the settings screen
        // twice (the second start re-created it). Receivers run on the main thread.
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandledAt < DUPLICATE_WINDOW_MS) return
        lastHandledAt = now

        context.getSharedPreferences("savedPrefs", Context.MODE_PRIVATE).edit { putBoolean("UI", true) }
        // NEW_TASK | CLEAR_TOP | SINGLE_TOP: an open settings screen is brought to the front.
        AppWaker.openSettings(context)
    }

    private companion object {
        /** Used by dialers before Android 10 (TelephonyManager.ACTION_SECRET_CODE since API 29). */
        const val LEGACY_SECRET_CODE_ACTION = "android.provider.Telephony.SECRET_CODE"
        const val DUPLICATE_WINDOW_MS = 2_000L

        private var lastHandledAt = -DUPLICATE_WINDOW_MS
    }
}
