package vasyl.titles.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import vasyl.titles.Privileges
import java.util.concurrent.Executors

/**
 * Shizuku or root in place of the system UID, for the standard flavor (see [ShellOp] for all that
 * it does).
 *
 * Rules:
 * - Used only when the user turned it on ([isEnabled], the "Use Shizuku / root" setting), except
 *   to undo what it did (the touch opacity limit is restored after the setting is turned off).
 * - Shizuku first (when it runs and the app has its permission), root otherwise.
 * - Everything runs on one thread of its own; nothing here may run on the main thread.
 */
object PrivilegedShell {

    enum class Result { OK, FAILED, UNAVAILABLE }

    private const val TAG = "PrivilegedShell"
    private const val PREFS = "savedPrefs"
    const val PREF_ENABLED = "privileged_helper"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PrivilegedShell").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** The user turned "Use Shizuku / root" on. Not for the system UID, which needs neither. */
    fun isEnabled(context: Context): Boolean =
        !Privileges.isSystemUid &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false)

    /** Prepares Shizuku's listeners. Main thread. */
    fun init(context: Context) {
        ShizukuHost.init(context)
    }

    /**
     * Whether the setting is offered: Shizuku is installed (any build: its package, a package that
     * defines its API permission, or its server, e.g. Sui) or root is there. Blocking.
     */
    fun isOfferedBlocking(context: Context): Boolean {
        if (Privileges.isSystemUid) return false
        return isShizukuInstalled(context.packageManager) || ShizukuHost.isRunning() || RootShell.isAvailable()
    }

    /** Shizuku runs and this app may use it. */
    fun hasShizuku(): Boolean = ShizukuHost.hasPermission()

    /** Asks for Shizuku's permission when Shizuku runs; main thread (see [ShizukuHost.requestPermission]). */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit) = ShizukuHost.requestPermission(onResult)

    /** Runs [op] on this class's thread; [onResult] comes on the main thread. */
    fun submit(context: Context, op: ShellOp, arg: String? = null, onResult: ((Result) -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            val result = runBlocking(app, op, arg)
            if (onResult != null) main.post { onResult(result) }
        }
    }

    /** Runs [op]: Shizuku, then root. Blocking: on this class's thread or another worker only. */
    fun runBlocking(context: Context, op: ShellOp, arg: String? = null): Result {
        val argv = ShellCommands.argv(op, context.packageName, DmtShellService.LISTENER_CLASS, arg)
            ?: return Result.FAILED
        val viaShizuku = ShizukuHost.run(op, arg)
        val result = when {
            viaShizuku == true -> Result.OK
            viaShizuku == false -> Result.FAILED
            RootShell.isAvailable() -> if (RootShell.run(argv)) Result.OK else Result.FAILED
            else -> Result.UNAVAILABLE
        }
        Log.i(TAG, "$op${arg?.let { " $it" }.orEmpty()}: $result")
        return result
    }

    /** Runs [block] on this class's thread. */
    fun execute(block: () -> Unit) = executor.execute(block)

    private fun isShizukuInstalled(pm: PackageManager): Boolean {
        val byPackage = try {
            @Suppress("DEPRECATION") // the PackageInfoFlags overload needs API 33
            pm.getPackageInfo(ShizukuHost.SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (byPackage) return true
        return try {
            pm.getPermissionInfo(ShizukuHost.SHIZUKU_API_PERMISSION, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
