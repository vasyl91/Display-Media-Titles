package vasyl.titles.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import vasyl.titles.BuildConfig
import vasyl.titles.Privileges
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of Shizuku (modelled on the FYT Launcher Mod's WindowHostVdShizuku): its
 * permission and the binding of [DmtShellService].
 *
 * Rules:
 * - Not used by the system UID (the system flavors need no shell).
 * - Shizuku's permission is only asked for after the user's action (the "Use Shizuku / root"
 *   setting); a permission denied for good is not asked for again.
 * - The service is bound when a command needs it and dies with the app's process (no daemon).
 * - Listeners and the binding run on the main thread; [run] is called on PrivilegedShell's thread.
 */
internal object ShizukuHost {

    private const val TAG = "PrivilegedShell"
    private const val PERMISSION_REQUEST_CODE = 0x7d
    private const val BIND_TIMEOUT_MS = 5_000L

    /** Shizuku and its forks that keep its package name (Shizuku Next). */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** The permission every Shizuku build defines, whatever its package is called. */
    const val SHIZUKU_API_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    private val main = Handler(Looper.getMainLooper())

    // main thread only
    private var app: Context? = null
    private var bound = false
    private var args: Shizuku.UserServiceArgs? = null
    private val waiting = ArrayList<CountDownLatch>()
    private val permissionCallbacks = ArrayList<(Boolean) -> Unit>()

    @Volatile
    private var service: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Shizuku shell connected")
            service = binder
            releaseWaiting()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    /** Registers Shizuku's listeners. Main thread, idempotent. */
    fun init(context: Context) {
        if (app != null || Privileges.isSystemUid) return
        app = context.applicationContext
        try {
            Shizuku.addBinderDeadListener({
                service = null
                bound = false
                releaseWaiting()
            }, main)
            Shizuku.addRequestPermissionResultListener({ requestCode, result ->
                if (requestCode != PERMISSION_REQUEST_CODE) return@addRequestPermissionResultListener
                val granted = result == PackageManager.PERMISSION_GRANTED
                Log.i(TAG, "Shizuku permission ${if (granted) "granted" else "denied"}")
                val callbacks = permissionCallbacks.toList()
                permissionCallbacks.clear()
                callbacks.forEach { it(granted) }
            }, main)
        } catch (t: Throwable) { // the API is part of the APK; this only guards a broken install
            Log.w(TAG, "Shizuku API unavailable", t)
        }
    }

    /** Shizuku's server runs and is recent enough (v11+). Any thread. */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (t: Throwable) {
        false
    }

    /** Shizuku runs and this app may use it. Any thread. */
    fun hasPermission(): Boolean = isRunning() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * Asks for Shizuku's permission (Shizuku shows its own dialog). [onResult] says whether it is
     * granted; false at once when Shizuku does not run or the user refused it for good. Main thread.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (app == null || !isRunning()) return onResult(false)
        if (hasPermission()) return onResult(true)
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) return onResult(false) // denied for good
            permissionCallbacks += onResult
            if (permissionCallbacks.size == 1) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku permission request failed", t)
            permissionCallbacks.remove(onResult)
            onResult(false)
        }
    }

    /**
     * Runs [op] in [DmtShellService], binding it first. Null when Shizuku cannot be used (no
     * permission, the service did not come within [BIND_TIMEOUT_MS]). Not on the main thread.
     */
    fun run(op: ShellOp, arg: String?): Boolean? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Shizuku calls block: not on the main thread" }
        if (!hasPermission()) return null
        val binder = awaitService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DmtShellService.DESCRIPTOR)
            data.writeInt(op.ordinal)
            data.writeString(arg)
            binder.transact(DmtShellService.TX_RUN, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (e: Exception) { // RemoteException, a dead service
            Log.w(TAG, "Shizuku shell call failed: $e")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun awaitService(): IBinder? {
        service?.takeIf { it.isBinderAlive }?.let { return it }
        val latch = CountDownLatch(1)
        main.post { bind(latch) }
        latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return service?.takeIf { it.isBinderAlive }
    }

    private fun bind(latch: CountDownLatch) {
        val context = app
        if (context == null || service?.isBinderAlive == true) {
            latch.countDown()
            return
        }
        waiting += latch
        if (bound) return
        try {
            bound = true
            Shizuku.bindUserService(args(context), connection)
        } catch (t: Throwable) {
            bound = false
            Log.w(TAG, "binding the Shizuku shell failed", t)
            releaseWaiting()
        }
    }

    private fun releaseWaiting() {
        waiting.forEach { it.countDown() }
        waiting.clear()
    }

    private fun args(context: Context): Shizuku.UserServiceArgs =
        args ?: Shizuku.UserServiceArgs(ComponentName(context.packageName, DmtShellService::class.java.name))
            .daemon(false) // dies with the app's process
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            // A new build must not keep talking to the service of the old one.
            .version(BuildConfig.VERSION_CODE)
            .also { args = it }
}
