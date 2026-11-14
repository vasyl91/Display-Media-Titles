package vasyl.titles.privileged

/**
 * The decisions about Android 12's touch opacity limit (Settings.Global
 * "maximum_obscuring_opacity_for_touch"). Free of android.* and unit tested; a port of the FYT
 * Launcher Mod's StatsOverlayAlpha decisions.
 *
 * Since Android 12 the system caps a not-touchable overlay of another app at this limit, 0.8 by
 * default, unless it is a trusted overlay (DisplayPolicy.adjustWindowParamsLw). The system flavors
 * make the title a trusted overlay; the standard flavor makes its window touchable instead, so it
 * stays opaque but swallows the touches on it. With "Use Shizuku / root" the limit is set to 1
 * while the title is shown, so the title is opaque and lets touches through, and it is put back
 * afterwards.
 *
 * Rules:
 * - Only while the title is shown is the limit raised; the value before is kept once (the first
 *   raise) and written back after.
 * - A limit someone else changed after the raise is left alone ([LimitAction.FORGET]).
 * - Writing goes directly with WRITE_SECURE_SETTINGS, otherwise through Shizuku or root.
 */
object ObscuringLimitPolicy {

    const val DEFAULT_LIMIT = 0.8f

    enum class Route { NOT_NEEDED, TRUSTED, WRITE_DIRECT, HELPER, UNAVAILABLE }

    enum class LimitAction { NOTHING, RAISE, RESTORE, FORGET }

    fun route(sdk: Int, systemUid: Boolean, writeSecureSettings: Boolean, helper: Boolean, currentLimit: Float): Route =
        when {
            sdk < 31 || currentLimit >= 1f -> Route.NOT_NEEDED
            systemUid -> Route.TRUSTED
            writeSecureSettings -> Route.WRITE_DIRECT
            helper -> Route.HELPER
            else -> Route.UNAVAILABLE
        }

    /** The limit in a Settings value: null, empty or not a number is the default, 0.8. */
    fun parseLimit(value: String?): Float = value?.trim()?.toFloatOrNull() ?: DEFAULT_LIMIT

    /** The title is shown and the limit below 1: someone lowered it after the raise. */
    fun needsRaise(windowShown: Boolean, currentLimit: Float): Boolean = windowShown && currentLimit < 1f

    fun limitAction(windowShown: Boolean, raisedByUs: Boolean, currentValue: String?): LimitAction {
        val full = parseLimit(currentValue) >= 1f
        return when {
            windowShown -> if (full) LimitAction.NOTHING else LimitAction.RAISE
            !raisedByUs -> LimitAction.NOTHING
            full -> LimitAction.RESTORE
            else -> LimitAction.FORGET
        }
    }
}

/**
 * The [DELAY_MS] the title may stay gone before the limit goes back: a track change or a short
 * pause does not restore and raise it each time. The time is given from outside. Thread safe.
 */
class HideDebounce {
    private var pending = false
    private var deadline = 0L

    /** The title went away at [now]: the limit may go back [DELAY_MS] after the last such call. */
    @Synchronized
    fun onHidden(now: Long) {
        pending = true
        deadline = now + DELAY_MS
    }

    /** The title is back: nothing to restore. */
    @Synchronized
    fun onShown() {
        pending = false
    }

    /** True once, when the restore is due. */
    @Synchronized
    fun due(now: Long): Boolean {
        if (pending && now >= deadline) {
            pending = false
            return true
        }
        return false
    }

    companion object {
        const val DELAY_MS = 3_000L
    }
}
