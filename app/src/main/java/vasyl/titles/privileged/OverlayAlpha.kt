package vasyl.titles.privileged

import android.content.Context
import android.database.ContentObserver
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import vasyl.titles.Privileges
import vasyl.titles.privileged.ObscuringLimitPolicy.LimitAction
import vasyl.titles.privileged.ObscuringLimitPolicy.Route

/**
 * Raises Android 12's touch opacity limit to 1 while the title is shown and puts it back after
 * (see [ObscuringLimitPolicy]; a port of the FYT Launcher Mod's StatsOverlayAlpha). Only for the
 * standard flavor with "Use Shizuku / root" on; the system flavors make the title a trusted
 * overlay instead.
 *
 * Rules:
 * - Raised only while the option is on; put back whatever the option says now, so turning it off
 *   restores the limit at once.
 * - Put back [HideDebounce.DELAY_MS] after the title went, at once when the listener stops or
 *   starts (a process that died with the limit raised) and when the option is turned off.
 * - While the title is shown, a limit lowered by someone else is raised again (an observer on the
 *   setting, and a look every minute).
 * - The writes run on PrivilegedShell's thread; the preferences are written there with commit().
 * - After a raise, and after a restore, [listener] hears on the main thread whether the limit is
 *   full: the title's window lets touches through at full opacity only while it is.
 */
object OverlayAlpha {

    private const val TAG = "OverlayAlpha"
    private const val PREFS = "savedPrefs"
    private const val KEY_RAISED = "obscuring_raised"
    private const val KEY_ORIGINAL = "obscuring_original"
    private const val CHECK_INTERVAL_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val debounce = HideDebounce()

    @Volatile private var shown = false
    @Volatile private var app: Context? = null
    /** Told on the main thread whether the limit is full now (the notification listener). */
    @Volatile var listener: ((Boolean) -> Unit)? = null

    // main thread only
    private var observer: ContentObserver? = null

    private val hideTask = Runnable {
        if (debounce.due(SystemClock.uptimeMillis())) {
            shown = false
            stopWatching()
            submit()
        }
    }

    private val checkTask = object : Runnable {
        override fun run() {
            if (!shown) return
            submit()
            main.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** The limit is at least 1 now: a not-touchable title keeps its full opacity. Any thread. */
    fun isLimitFull(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && currentLimit(context) >= 1f

    /** Whether the limit is handled here: standard flavor, Android 12+, the option on. */
    fun isWanted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Privileges.isSystemUid && PrivilegedShell.isEnabled(context)

    /** Main thread. A title window was added: the limit goes to 1, then [listener] hears it. */
    fun onWindowShown(context: Context) {
        if (!isWanted(context)) return
        app = context.applicationContext
        main.removeCallbacks(hideTask)
        debounce.onShown()
        shown = true
        startWatching(context.applicationContext)
        submit()
    }

    /** Main thread. The title window was removed: the limit goes back a moment later. */
    fun onWindowHidden(context: Context) {
        if (!shown) return
        app = context.applicationContext
        debounce.onHidden(SystemClock.uptimeMillis())
        main.removeCallbacks(hideTask)
        main.postDelayed(hideTask, HideDebounce.DELAY_MS)
    }

    /** Main thread. The listener stops or starts, or the option was turned off: back at once. */
    fun onWindowHiddenNow(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || Privileges.isSystemUid) return
        app = context.applicationContext
        main.removeCallbacks(hideTask)
        shown = false
        stopWatching()
        submit()
    }

    private fun startWatching(context: Context) {
        if (observer == null) {
            val watcher = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    val c = app ?: return
                    if (ObscuringLimitPolicy.needsRaise(shown, currentLimit(c))) submit()
                }
            }
            try {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(ShellCommands.OBSCURING_LIMIT_KEY), false, watcher
                )
                observer = watcher
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot watch the limit: $e")
            }
        }
        main.removeCallbacks(checkTask)
        main.postDelayed(checkTask, CHECK_INTERVAL_MS)
    }

    private fun stopWatching() {
        main.removeCallbacks(checkTask)
        val watcher = observer ?: return
        observer = null
        try {
            app?.contentResolver?.unregisterContentObserver(watcher)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot stop watching the limit: $e")
        }
    }

    private fun submit() {
        PrivilegedShell.execute(::sync)
    }

    /** On PrivilegedShell's thread: brings the limit to what [shown] says. */
    private fun sync() {
        val context = app ?: return
        try {
            val wantShown = shown && PrivilegedShell.isEnabled(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raised = prefs.getBoolean(KEY_RAISED, false)
            val current = Settings.Global.getString(context.contentResolver, ShellCommands.OBSCURING_LIMIT_KEY)
            val action = ObscuringLimitPolicy.limitAction(wantShown, raised, current)
            if (action == LimitAction.NOTHING) {
                if (wantShown) postLimit(true)
                return
            }
            if (action == LimitAction.FORGET) {
                prefs.edit().remove(KEY_RAISED).remove(KEY_ORIGINAL).commit()
                Log.i(TAG, "FORGET: the limit is $current, changed by someone else; left alone")
                return
            }
            // How to write, whatever the limit is now (route() answers NOT_NEEDED for a full one).
            val route = ObscuringLimitPolicy.route(
                Build.VERSION.SDK_INT, false, Privileges.canWriteSecureSettings(context),
                PrivilegedShell.hasShizuku() || PrivilegedShell.hasRootBlocking(), 0f,
            )
            if (route == Route.UNAVAILABLE || route == Route.NOT_NEEDED) {
                Log.w(TAG, "$action: neither WRITE_SECURE_SETTINGS nor Shizuku / root, the limit stays $current")
                return
            }
            if (action == LimitAction.RAISE) {
                if (!raised) {
                    val editor = prefs.edit().putBoolean(KEY_RAISED, true)
                    if (current == null) editor.remove(KEY_ORIGINAL) else editor.putString(KEY_ORIGINAL, current)
                    editor.commit()
                } else {
                    Log.w(TAG, "RAISE again: the limit was $current while the title was shown")
                }
                val ok = write(context, route, "1")
                var after = currentLimit(context)
                var waits = 0
                while (after < 1f && shown && waits++ < 10) { // the window manager observes the setting
                    SystemClock.sleep(100)
                    after = currentLimit(context)
                }
                Log.i(TAG, "RAISE: route $route, $current -> $after, written $ok")
                if (after >= 1f && shown) postLimit(true)
            } else { // RESTORE
                val original = prefs.getString(KEY_ORIGINAL, null)
                val ok = write(context, route, original)
                Log.i(TAG, "RESTORE: route $route, $current -> ${original ?: "(deleted)"}, written $ok")
                if (ok) {
                    prefs.edit().remove(KEY_RAISED).remove(KEY_ORIGINAL).commit()
                    postLimit(currentLimit(context) >= 1f)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "sync failed", e)
        }
    }

    private fun postLimit(full: Boolean) {
        main.post { listener?.invoke(full) }
    }

    /** [value] null: delete the setting (Android's default applies). */
    private fun write(context: Context, route: Route, value: String?): Boolean {
        if (route == Route.WRITE_DIRECT) {
            try {
                if (Settings.Global.putString(context.contentResolver, ShellCommands.OBSCURING_LIMIT_KEY, value)) {
                    return true
                }
            } catch (e: RuntimeException) { // SecurityException
                Log.w(TAG, "direct write failed: $e")
            }
        }
        val plain = ShellCommands.limitValue(value)
        val result = if (plain == null) {
            PrivilegedShell.runBlocking(context, ShellOp.DELETE_OBSCURING_LIMIT)
        } else {
            PrivilegedShell.runBlocking(context, ShellOp.SET_OBSCURING_LIMIT, plain)
        }
        return result == PrivilegedShell.Result.OK
    }

    /** InputManager's limit (Android 12+), the default when it cannot be read. */
    private fun currentLimit(context: Context): Float = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch
                ?: ObscuringLimitPolicy.DEFAULT_LIMIT
        } else {
            1f
        }
    } catch (e: RuntimeException) {
        ObscuringLimitPolicy.DEFAULT_LIMIT
    }
}
