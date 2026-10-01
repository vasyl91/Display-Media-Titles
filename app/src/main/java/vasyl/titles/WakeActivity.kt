package vasyl.titles

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Invisible entry point of the app (Theme.NoDisplay). The launcher icon and the FYT "start apps at
 * boot" option (it starts the launcher activity) land here.
 *
 * Starting an activity brings the process up, clears the package's "stopped" state and moves the
 * app to an active standby bucket. This one draws nothing and finishes in onCreate. It is still an
 * activity start, though: its task comes to the front for a moment and the launcher is paused (no
 * manifest attribute can prevent that). To wake the app with nothing coming to the front, send the
 * broadcast handled by WakeReceiver instead.
 *
 * The settings screen is only opened when "Display UI" is checked or a required permission is still
 * missing.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Woken by ${referrer?.host ?: "unknown"}")

        AppWaker.wake(this)
        if (AppWaker.shouldShowSettings(this)) AppWaker.openSettings(this)

        // Theme.NoDisplay requires finish() before the activity would be resumed.
        finish()
        @Suppress("DEPRECATION") // no transition either way; the replacement needs API 34
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val TAG = "WakeActivity"
    }
}
