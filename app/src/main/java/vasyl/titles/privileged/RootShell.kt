package vasyl.titles.privileged

import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root as the privileged shell: "su -c" with a command of [ShellCommands] (modelled on the FYT
 * Launcher Mod's StatsOverlayAlpha and SyuMediaFiles).
 *
 * Rules:
 * - Never on the main thread: finding su reads the file system, and su may wait for the user to
 *   grant root (Magisk's dialog).
 * - A command gets [TIMEOUT_S] seconds; a su that nobody answers is destroyed then.
 */
internal object RootShell {

    private const val TAG = "PrivilegedShell"
    private const val TIMEOUT_S = 15L
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/vendor/bin/su", "/su/bin/su",
    )

    @Volatile
    private var available: Boolean? = null

    /** A su binary exists (whether root will be granted is only known when it is used). */
    fun isAvailable(): Boolean {
        available?.let { return it }
        notOnMain()
        return SU_PATHS.any { File(it).exists() }.also { available = it }
    }

    fun run(argv: List<String>): Boolean {
        notOnMain()
        if (!isAvailable()) return false
        val line = ShellCommands.commandLine(argv)
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", line).redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "su timed out: $line")
                false
            } else {
                val exit = process.exitValue()
                if (exit != 0) {
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    Log.w(TAG, "su exit $exit: $line: ${output.take(300)}")
                }
                exit == 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "su failed: $line: $e")
            false
        } finally {
            process?.destroy()
        }
    }

    private fun notOnMain() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "root calls block: not on the main thread" }
    }
}
