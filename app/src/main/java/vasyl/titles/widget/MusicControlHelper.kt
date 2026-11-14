package vasyl.titles.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vasyl.titles.FytOrigin
import vasyl.titles.FytPlayerBus
import vasyl.titles.MusicService
import vasyl.titles.NotificationListener
import vasyl.titles.excludeapps.AppScope
import vasyl.titles.fyt.Stock7870Source
import vasyl.titles.fyt.StockAction
import vasyl.titles.fyt.SyuMediaProtocol

/**
 * Handles the widget buttons. Every system call is guarded: a widget click must never crash the app.
 *
 * Rules:
 * - The buttons control the player whose track the widget shows: a media session, com.syu.music
 *   (7862 / 8581, its "launcher music" commands) or a stock source of the FYT 7870 (com.syu.media's
 *   players, BT, the radio; see Stock7870Source.control). The title overlay only reads, and
 *   nothing is ever played without a press.
 * - A 7870 source that does not run or cannot be reached is not brought up: its page in
 *   com.syu.media is opened instead, without playing anything.
 */
object MusicControlHelper {

    private const val TAG = "MusicControlHelper"
    private const val SYU_MUSIC = MusicService.MUSIC_PKG

    /** How long a press waits for a 7870 source to take it before its page is opened. */
    private const val STOCK_CONTROL_WAIT_MS = 1_000L

    suspend fun onPreviousClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> {
                val origin = stock7870Origin()
                if (origin != null) controlStock(context, origin, StockAction.PREVIOUS)
                else sendFytCommand(context, MusicService.PREVMUSIC)
            }
            NotificationListener.SOURCE_CONTROLLER -> withController(context) { it.transportControls.skipToPrevious() }
        }
    }

    suspend fun onPlayPauseClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> {
                // The state the titles got from the player: MusicService.state only knows the
                // player's own updates, not the launcher's relay, and was always "not playing" then.
                showPressed(context, fytPlaying(), ::fytPlaying)
                val origin = stock7870Origin()
                if (origin != null) controlStock(context, origin, StockAction.PLAY_PAUSE)
                else sendFytCommand(context, MusicService.PLAYPAUSEMUSIC)
            }

            NotificationListener.SOURCE_CONTROLLER -> withController(context) { controller ->
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                showPressed(context, playing) { controllerPlaying(context) }
                if (playing) controller.transportControls.pause() else controller.transportControls.play()
            }
        }
    }

    suspend fun onNextClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> {
                val origin = stock7870Origin()
                if (origin != null) controlStock(context, origin, StockAction.NEXT)
                else sendFytCommand(context, MusicService.NEXTMUSIC)
            }
            NotificationListener.SOURCE_CONTROLLER -> withController(context) { it.transportControls.skipToNext() }
        }
    }

    fun openPlayer(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> {
                val origin = stock7870Origin()
                if (origin != null) openStockPage(context, origin) else openAppByPackageName(context, SYU_MUSIC)
            }

            NotificationListener.SOURCE_CONTROLLER -> {
                val pkg = NotificationListener.activeControllerPackage
                if (pkg.isNotEmpty()) openAppByPackageName(context, pkg)
            }

            else -> {
                val lastController = context.getSharedPreferences("savedPrefs", Context.MODE_PRIVATE)
                    .getString("lastMediaController", null)
                if (!lastController.isNullOrEmpty()) openAppByPackageName(context, lastController)
            }
        }
    }

    private val hold = PlayPauseHold()

    /** The opposite symbol at once, the player's real state [PlayPauseHold.HOLD_MS] later. */
    private fun showPressed(context: Context, playingAtPress: Boolean, actual: () -> Boolean) {
        val app = context.applicationContext
        val token = hold.press()
        updateWidgetPlayState(app, PlayPauseHold.symbolAfterPress(playingAtPress))
        AppScope.launch {
            delay(PlayPauseHold.HOLD_MS)
            if (hold.isLatest(token)) updateWidgetPlayState(app, actual())
        }
    }

    private fun fytPlaying(): Boolean = FytPlayerBus.last.value?.playing == true

    private fun controllerPlaying(context: Context): Boolean {
        var playing = false
        withController(context) { playing = it.playbackState?.state == PlaybackState.STATE_PLAYING }
        return playing
    }

    /** The FYT 7870's stock source whose track the widget shows; null for com.syu.music. */
    private fun stock7870Origin(): FytOrigin? = FytPlayerBus.last.value?.origin?.takeIf { it.is7870Stock }

    /** Sends [action] to a 7870 source; its page opens when it does not take it within a second. */
    private suspend fun controlStock(context: Context, origin: FytOrigin, action: StockAction) {
        val result = CompletableDeferred<Boolean>()
        Stock7870Source.control(origin, action) { result.complete(it) }
        val sent = withTimeoutOrNull(STOCK_CONTROL_WAIT_MS) { result.await() } == true
        if (!sent) {
            Log.i(TAG, "$origin did not take $action: opening its page")
            openStockPage(context, origin)
        }
    }

    /**
     * The page of a 7870 source in com.syu.media (the FYT Launcher Mod's StockSources.pageAction):
     * all of them are actions of its main activity. Without it, com.syu.media's launch activity.
     */
    @Suppress("DEPRECATION") // the ResolveInfoFlags overload of resolveActivity() needs API 33
    private fun openStockPage(context: Context, origin: FytOrigin): Boolean {
        val action = when (origin) {
            FytOrigin.MEDIA_MUSIC -> "com.syu.music"
            FytOrigin.MEDIA_ONLINE -> "com.syu.online"
            FytOrigin.MEDIA_BT -> "com.syu.btmusic"
            FytOrigin.RADIO -> "com.syu.radio"
            else -> null
        }
        return try {
            val pm = context.packageManager
            val page = action?.let { Intent(it).setPackage(SyuMediaProtocol.PACKAGE) }
                ?.takeIf { pm.resolveActivity(it, 0) != null }
                ?: pm.getLaunchIntentForPackage(SyuMediaProtocol.PACKAGE)
                ?: return false
            context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) { // ActivityNotFoundException, background start restrictions
            Log.w(TAG, "Cannot open the page of $origin", e)
            false
        }
    }

    fun openAppByPackageName(context: Context, packageName: String): Boolean {
        return try {
            val packageManager = context.packageManager
            @Suppress("DEPRECATION") // the PackageInfoFlags overload needs API 33
            packageManager.getPackageInfo(packageName, 0) // throws if the app is not installed

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                // No launcher activity: open the app info page instead.
                openAppInfoInSettings(context, packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: RuntimeException) { // ActivityNotFoundException, SecurityException
            Log.w(TAG, "Cannot open $packageName", e)
            false
        }
    }

    fun openAppInfoInSettings(context: Context, packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:$packageName".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot open app info of $packageName", e)
            false
        }
    }

    private fun sendFytCommand(context: Context, action: String) {
        try {
            context.startService(Intent(action).setPackage(SYU_MUSIC))
        } catch (e: RuntimeException) { // IllegalStateException (background start), SecurityException
            Log.w(TAG, "Cannot send $action to $SYU_MUSIC", e)
        }
    }

    /** Runs [block] with the controller of the active player; getActiveSessions throws without access. */
    private inline fun withController(context: Context, block: (MediaController) -> Unit) {
        val pkg = NotificationListener.activeControllerPackage
        if (pkg.isEmpty()) return
        try {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(context, NotificationListener::class.java)
            manager.getActiveSessions(component).firstOrNull { it.packageName == pkg }?.let(block)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media control failed for $pkg", e)
        }
    }
}
