package vasyl.titles.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log

/**
 * The binding to com.syu.ms, FYT's MCU service, read only (see [MsProtocol]): the sound channel,
 * the radio's station and the phone's BT song. A hand-written client like the FYT Launcher Mod's
 * com.syu.remote.RemoteTools, limited to the codes this app needs.
 *
 * Rules:
 * - Every binder call goes out on [worker]. Updates arrive on binder threads and are handed to the
 *   worker; each module has a callback of its own, as an update does not name its module, and
 *   only the registered codes are passed on (the 7870 sends every code to every callback).
 * - After the connection the current channel is asked for (code 0 of the main module), as it is
 *   not known whether registering makes com.syu.ms send the current values.
 * - When com.syu.ms goes away [Listener.onGone] says so; the binding connects again by itself. A
 *   binding that died is made again after [REBIND_MS]. A SecurityException (not bindable by this
 *   app) ends the attempts: BT and the radio are not available then.
 * - [cmd] sends a command of a widget button (see [StockControls]); it says whether it went out.
 * - After [stop] nothing is reported any more.
 */
internal class MsToolkitClient(
    private val app: Context,
    private val worker: Handler,
    private val listener: Listener,
) : ServiceConnection {

    /** Called on the worker thread. */
    interface Listener {
        fun onUpdate(module: Int, code: Int, ints: IntArray?, strs: Array<String?>?)
        fun onGone()
    }

    private val modules = intArrayOf(MsProtocol.MODULE_MAIN, MsProtocol.MODULE_RADIO, MsProtocol.MODULE_BT)
    private val stubs: Map<Int, ModuleCallback> = modules.toList().associateWith { ModuleCallback(it) }

    @Volatile
    private var stopped = false

    // worker thread only
    private val remotes = HashMap<Int, IBinder>()

    fun start() {
        worker.post { bind() }
    }

    /** Main thread; the worker may be quit right after this. */
    fun stop() {
        stopped = true
        unbindQuietly()
        worker.post {
            for ((module, remote) in remotes) {
                for (code in codesOf(module)) unregister(remote, module, code)
            }
            remotes.clear()
        }
    }

    /** Registers the codes of [module] again, so com.syu.ms reports what it holds. Any thread. */
    fun registerAgain(module: Int) {
        if (stopped) return
        worker.post {
            val remote = remotes[module] ?: return@post
            for (code in codesOf(module)) register(remote, module, code)
        }
    }

    /** IRemoteModule.cmd(code, ints, null, null), one-way. Worker thread only. */
    fun cmd(module: Int, code: Int, ints: List<Int>): Boolean {
        if (stopped) return false
        val remote = remotes[module] ?: return false
        return oneWay(remote, MsProtocol.TX_CMD) {
            writeInt(code)
            writeIntArray(ints.toIntArray())
            writeFloatArray(null)
            writeStringArray(null)
        }
    }

    private fun bind() {
        if (stopped) return
        val bound = try {
            app.bindService(Intent(MsProtocol.ACTION).setPackage(MsProtocol.PACKAGE), this, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "${MsProtocol.PACKAGE} is not bindable by this app: BT and radio are not read", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "bindService failed", e)
            false
        }
        if (bound) Log.d(TAG, "bound ${MsProtocol.ACTION}") else unbindQuietly()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        worker.post {
            if (stopped || service == null) return@post
            remotes.clear()
            for (module in modules) {
                val remote = remoteModule(service, module) ?: continue
                remotes[module] = remote
                for (code in codesOf(module)) register(remote, module, code)
            }
            Log.d(TAG, "connected, modules ${remotes.keys}")
            remotes[MsProtocol.MODULE_MAIN]?.let { main ->
                get(main, MsProtocol.MAIN_APP_ID)?.let { ints ->
                    listener.onUpdate(MsProtocol.MODULE_MAIN, MsProtocol.MAIN_APP_ID, ints, null)
                }
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "disconnected")
        worker.post { dropRemotes() }
    }

    override fun onBindingDied(name: ComponentName?) {
        Log.d(TAG, "binding died, binding again in $REBIND_MS ms")
        worker.post {
            dropRemotes()
            unbindQuietly()
            if (!stopped) worker.postDelayed({ bind() }, REBIND_MS)
        }
    }

    private fun dropRemotes() {
        if (remotes.isEmpty()) return
        remotes.clear()
        if (!stopped) listener.onGone()
    }

    private fun codesOf(module: Int): IntArray = when (module) {
        MsProtocol.MODULE_MAIN -> MsProtocol.MAIN_CODES
        MsProtocol.MODULE_RADIO -> MsProtocol.RADIO_CODES
        MsProtocol.MODULE_BT -> MsProtocol.BT_CODES
        else -> IntArray(0)
    }

    /** IRemoteToolkit.getRemoteModule(module). */
    private fun remoteModule(toolkit: IBinder, module: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MsProtocol.ITOOLKIT)
            data.writeInt(module)
            toolkit.transact(MsProtocol.TX_GET_REMOTE_MODULE, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } catch (e: RemoteException) {
            Log.w(TAG, "module $module not available: $e")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "module $module not available: $e")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** IRemoteModule.register(callback, code, 1), one-way. */
    private fun register(remote: IBinder, module: Int, code: Int) {
        oneWay(remote, MsProtocol.TX_REGISTER) {
            writeStrongBinder(stubs.getValue(module))
            writeInt(code)
            writeInt(1)
        }
    }

    /** IRemoteModule.unregister(callback, code), one-way. */
    private fun unregister(remote: IBinder, module: Int, code: Int) {
        oneWay(remote, MsProtocol.TX_UNREGISTER) {
            writeStrongBinder(stubs.getValue(module))
            writeInt(code)
        }
    }

    private inline fun oneWay(remote: IBinder, code: Int, write: Parcel.() -> Unit): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MsProtocol.IMODULE)
            data.write()
            remote.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: RemoteException) {
            Log.w(TAG, "call $code failed: $e")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "call $code failed: $e")
            false
        } finally {
            data.recycle()
        }
    }

    /** IRemoteModule.get(code, [0], null, null): the ints of the answer, null without one. */
    private fun get(remote: IBinder, code: Int): IntArray? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MsProtocol.IMODULE)
            data.writeInt(code)
            data.writeIntArray(intArrayOf(0))
            data.writeFloatArray(null)
            data.writeStringArray(null)
            remote.transact(MsProtocol.TX_GET, data, reply, 0)
            reply.readException()
            if (reply.readInt() != 0) reply.createIntArray() else null
        } catch (e: RemoteException) {
            Log.w(TAG, "get $code failed: $e")
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "get $code failed: $e")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun unbindQuietly() {
        try {
            app.unbindService(this)
        } catch (_: RuntimeException) { // IllegalArgumentException: not bound
        }
    }

    /** IModuleCallback of one module, implemented by hand. One-way: nothing to answer. */
    private inner class ModuleCallback(private val module: Int) : Binder() {
        init {
            attachInterface(null, MsProtocol.ICALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(MsProtocol.ICALLBACK)
                return true
            }
            if (code != MsProtocol.CB_UPDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(MsProtocol.ICALLBACK)
            val updateCode = data.readInt()
            val ints = data.createIntArray()
            data.createFloatArray()
            val strs = data.createStringArray()
            if (!stopped && updateCode in codesOf(module)) {
                worker.post { if (!stopped) listener.onUpdate(module, updateCode, ints, strs) }
            }
            return true
        }
    }

    private companion object {
        const val TAG = "DmtSyuMs"
        const val REBIND_MS = 2000L
    }
}
