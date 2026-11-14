package vasyl.titles.privileged

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The privileged shell as a Shizuku UserService (see [ShizukuHost]; modelled on the FYT Launcher
 * Mod's WindowHostVdShizukuService).
 *
 * Shizuku starts this class from the app's APK in a process of its own, as Shizuku's uid: 2000
 * (shell) when Shizuku was started over ADB, 0 when it runs with root. It never runs in the app's
 * process.
 *
 * Rules:
 * - One call, [TX_RUN]: an operation's number. The command is built here by [ShellCommands] for
 *   the app's own package and listener; no text from the caller is ever run.
 * - Only the app's own uid may call it.
 * - A command gets [TIMEOUT_S] seconds.
 */
class DmtShellService private constructor(private val pkg: String, private val appUid: Int) : Binder() {

    /** Shizuku 13+ passes the app's package Context. */
    constructor(context: Context) : this(context.packageName, context.applicationInfo?.uid ?: -1)

    init {
        attachInterface(null, DESCRIPTOR)
        Log.i(TAG, "privileged shell started: uid=${Process.myUid()}")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TX_SHIZUKU_DESTROY) {
            Log.i(TAG, "privileged shell destroyed")
            System.exit(0)
            return true
        }
        if (code != TX_RUN) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        val opIndex = data.readInt()
        val arg = data.readString()
        val caller = Binder.getCallingUid()
        val ok = if (caller != appUid) {
            Log.w(TAG, "refused a call from uid $caller")
            false
        } else {
            val op = ShellOp.entries.getOrNull(opIndex)
            val argv = op?.let { ShellCommands.argv(it, pkg, LISTENER_CLASS, arg) }
            if (argv == null) false else run(argv)
        }
        reply?.writeNoException()
        reply?.writeInt(if (ok) 1 else 0)
        return true
    }

    private fun run(argv: List<String>): Boolean = try {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            Log.w(TAG, "timed out: $argv")
            false
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = process.exitValue()
            if (exit != 0) Log.w(TAG, "exit $exit: $argv: ${output.take(300)}")
            exit == 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot run $argv", e)
        false
    }

    companion object {
        private const val TAG = "PrivilegedShell"
        const val DESCRIPTOR = "vasyl.titles.privileged.IDmtShell"
        const val TX_RUN = IBinder.FIRST_CALL_TRANSACTION

        /** IUserService.destroy(): Shizuku sends it when the service is unbound with remove = true. */
        private const val TX_SHIZUKU_DESTROY = 16777115
        private const val TIMEOUT_S = 10L

        /** The app's notification listener (the same class in every flavor). */
        const val LISTENER_CLASS = "vasyl.titles.NotificationListener"
    }
}
