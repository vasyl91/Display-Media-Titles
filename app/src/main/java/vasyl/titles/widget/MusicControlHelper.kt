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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vasyl.titles.FytPlayerBus
import vasyl.titles.MusicService
import vasyl.titles.NotificationListener
import vasyl.titles.excludeapps.AppScope

/** Handles the widget buttons. Every system call is guarded: a widget click must never crash the app. */
object MusicControlHelper {

    private const val TAG = "MusicControlHelper"
    private const val SYU_MUSIC = MusicService.MUSIC_PKG

    fun onPreviousClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> if (!isStock7870()) sendFytCommand(context, MusicService.PREVMUSIC)
            NotificationListener.SOURCE_CONTROLLER -> withController(context) { it.transportControls.skipToPrevious() }
        }
    }

    fun onPlayPauseClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> if (!isStock7870()) {
                // The state the titles got from the player: MusicService.state only knows the
                // player's own updates, not the launcher's relay, and was always "not playing" then.
                showPressed(context, fytPlaying(), ::fytPlaying)
                sendFytCommand(context, MusicService.PLAYPAUSEMUSIC)
            }

            NotificationListener.SOURCE_CONTROLLER -> withController(context) { controller ->
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                showPressed(context, playing) { controllerPlaying(context) }
                if (playing) controller.transportControls.pause() else controller.transportControls.play()
            }
        }
    }

    fun onNextClicked(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> if (!isStock7870()) sendFytCommand(context, MusicService.NEXTMUSIC)
            NotificationListener.SOURCE_CONTROLLER -> withController(context) { it.transportControls.skipToNext() }
        }
    }

    fun openPlayer(context: Context) {
        if (NotificationListener.excludeForWidget) return
        when (NotificationListener.source) {
            NotificationListener.SOURCE_FYT -> if (!isStock7870()) openAppByPackageName(context, SYU_MUSIC)

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

    /**
     * The FYT 7870's stock sources (com.syu.media, BT, radio) are only read, never controlled: their
     * buttons do nothing, and in particular the play / pause symbol is not flipped ahead of a state
     * that will never come.
     */
    private fun isStock7870(): Boolean = FytPlayerBus.last.value?.origin?.is7870Stock == true

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
