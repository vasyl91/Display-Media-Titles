package vasyl.titles.excludeapps

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Process-wide coroutine scope for work that must outlive a single component (e.g. widget updates
 * started by a short-lived service).
 *
 * Replaces the previous ad-hoc `CoroutineScope(Dispatchers.IO).launch { ... }` calls, which created
 * unowned scopes and let exceptions crash the process. A failing child never cancels its siblings
 * (SupervisorJob) and uncaught exceptions are logged instead of killing the app.
 */
object AppScope : CoroutineScope {

    private const val TAG = "AppScope"

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in application coroutine", throwable)
    }

    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.Default + exceptionHandler
}
