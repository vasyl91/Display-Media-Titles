package vasyl.titles.privileged

import java.util.Locale

/** The only things the privileged shell (Shizuku or root) is ever asked to do. */
enum class ShellOp {
    /** Lets the app write global / secure settings itself from then on. */
    GRANT_WRITE_SECURE_SETTINGS,

    /** Notification access for the app's own listener. */
    ALLOW_NOTIFICATION_LISTENER,

    /** Exempts the app from battery optimisation (Doze). */
    BATTERY_WHITELIST,

    /** "Display over other apps". */
    ALLOW_OVERLAY,

    /** All files access (MANAGE_EXTERNAL_STORAGE). */
    ALLOW_STORAGE,

    /** Sets maximum_obscuring_opacity_for_touch to the argument (0..1). */
    SET_OBSCURING_LIMIT,

    /** Deletes maximum_obscuring_opacity_for_touch (Android's default 0.8 applies again). */
    DELETE_OBSCURING_LIMIT,
}

/**
 * Builds the command of a [ShellOp]. Free of android.* and unit tested.
 *
 * Rules:
 * - Only these commands exist: no other text is ever run by the privileged shell, and the
 *   Shizuku service builds them itself from the operation's number.
 * - They only touch this app (its own package and its own listener) and the one global setting
 *   [OBSCURING_LIMIT_KEY].
 * - Every word of a command is checked to be a plain name or number, so it can be passed to
 *   "su -c" as one line without any shell quoting.
 */
object ShellCommands {

    const val OBSCURING_LIMIT_KEY = "maximum_obscuring_opacity_for_touch"

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val WORD = Regex("[A-Za-z0-9_.+/:-]+")

    /**
     * The command for [op], words as for ProcessBuilder (binaries by absolute path); null when
     * [pkg], [listener] or [arg] is not acceptable.
     */
    fun argv(op: ShellOp, pkg: String, listener: String, arg: String? = null): List<String>? {
        if (!PACKAGE.matches(pkg)) return null
        val words = when (op) {
            ShellOp.GRANT_WRITE_SECURE_SETTINGS ->
                listOf("/system/bin/pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS")
            ShellOp.ALLOW_NOTIFICATION_LISTENER -> {
                if (!PACKAGE.matches(listener)) return null
                listOf("/system/bin/cmd", "notification", "allow_listener", "$pkg/$listener")
            }
            ShellOp.BATTERY_WHITELIST -> listOf("/system/bin/cmd", "deviceidle", "whitelist", "+$pkg")
            ShellOp.ALLOW_OVERLAY -> listOf("/system/bin/appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
            ShellOp.ALLOW_STORAGE -> listOf("/system/bin/appops", "set", pkg, "MANAGE_EXTERNAL_STORAGE", "allow")
            ShellOp.SET_OBSCURING_LIMIT -> {
                val value = limitValue(arg) ?: return null
                listOf("/system/bin/settings", "put", "global", OBSCURING_LIMIT_KEY, value)
            }
            ShellOp.DELETE_OBSCURING_LIMIT -> listOf("/system/bin/settings", "delete", "global", OBSCURING_LIMIT_KEY)
        }
        return words.takeIf { list -> list.all { WORD.matches(it) } }
    }

    /** The same command as one line for "su -c". */
    fun commandLine(argv: List<String>): String = argv.joinToString(" ")

    /** [arg] as a limit between 0 and 1, written plainly ("1", "0.8"); null when it is none. */
    fun limitValue(arg: String?): String? {
        val value = arg?.trim()?.toFloatOrNull() ?: return null
        if (value.isNaN() || value < 0f || value > 1f) return null
        val text = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }
}
