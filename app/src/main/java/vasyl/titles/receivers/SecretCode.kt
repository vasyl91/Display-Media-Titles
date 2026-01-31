package vasyl.titles

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SecretCode : BroadcastReceiver() {
    
    private val notificationListener = NotificationListener()

    override fun onReceive(context: Context, intent: Intent) {
        if ("android.provider.Telephony.SECRET_CODE" == intent.action) {
            notificationListener.displayUI = true
            val settings = context.getSharedPreferences("savedPrefs", 0)
            val editor = settings.edit()
            editor.putBoolean("UI", true)
            editor.apply()
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
            }
        }
    }
}