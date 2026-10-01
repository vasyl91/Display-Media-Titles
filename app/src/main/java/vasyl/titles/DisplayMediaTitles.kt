package vasyl.titles

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vasyl.titles.excludeapps.AllAppsList
import vasyl.titles.excludeapps.AppFilter
import vasyl.titles.excludeapps.AppInfo
import vasyl.titles.excludeapps.AppScope

class DisplayMediaTitles : Application() {

    companion object {
        private const val TAG = "DisplayMediaTitles"

        private const val LISTENER_CHECK_DELAY_MS = 10_000L

        private lateinit var instance: DisplayMediaTitles

        @JvmStatic
        fun getInstance(): DisplayMediaTitles = instance

        @JvmStatic
        fun getContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Loading labels and icons of every launcher app took hundreds of milliseconds on the main
        // thread and delayed every component of the process (listener, widgets, MusicService).
        setAllAppsAsync()
        // Whatever started the process (boot, widget, FYT player, launcher): if the notification
        // listener is not connected by then, its binding is repaired (see ListenerGuard).
        ListenerGuard.check(this, LISTENER_CHECK_DELAY_MS)
    }

    /** Running app list load; main thread only. */
    private var loadAppsJob: Job? = null

    /**
     * Loads the launcher apps on a background thread and publishes them to [AllAppsList] on the main
     * thread (AllAppsList is still only touched from the main thread, as before). A load that is
     * already running is reused. Call on the main thread.
     */
    fun setAllAppsAsync(): Job {
        loadAppsJob?.takeIf { it.isActive }?.let { return it }
        return AppScope.launch(Dispatchers.IO) {
            val apps = loadLauncherApps()
            withContext(Dispatchers.Main) { publishApps(apps) }
        }.also { loadAppsJob = it }
    }

    /** Synchronous variant with the original behaviour (loads and publishes on the calling thread). */
    @Synchronized
    fun setAllApps() {
        publishApps(loadLauncherApps())
    }

    private fun publishApps(apps: List<AppInfo>) {
        val appFilter = AppFilter.loadByName(getString(R.string.app_filter_class))
        // Replaces the list (apps uninstalled meanwhile disappear) and notifies an open dialog.
        AllAppsList(appFilter).replaceAll(apps)
    }

    private fun loadLauncherApps(): List<AppInfo> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            // The ResolveInfoFlags overload needs API 33; minSdk is 26.
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: RuntimeException) {
            Log.e(TAG, "queryIntentActivities failed", e)
            return emptyList()
        }

        // Track unique package names to avoid duplicates (apps with several launcher activities).
        val addedPackages = HashSet<String>()
        val result = ArrayList<AppInfo>(activities.size)

        for (ri in activities) {
            val activityInfo = ri.activityInfo ?: continue
            val packageName = activityInfo.packageName

            // Skip launcher apps and duplicates.
            if (packageName.contains("launcher", ignoreCase = true)) continue
            if (!addedPackages.add(packageName)) continue

            try {
                val launchComponent = ComponentName(packageName, activityInfo.name)
                val launchIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(launchComponent)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val info = AppInfo().apply {
                    componentName = launchComponent
                    title = ri.loadLabel(pm)
                    iconBitmap = drawableToBitmap(ri.loadIcon(pm))
                    // Set the intent so getPackageName() works.
                    this.intent = launchIntent
                }
                result.add(info)
            } catch (e: RuntimeException) {
                // A single broken package (e.g. uninstalled meanwhile) must not abort the whole list.
                Log.w(TAG, "Skipping $packageName", e)
            }
        }
        return result
    }

    fun drawableToBitmap(drawable: Drawable?): Bitmap {
        drawable ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // 48dp icon.
        val iconSize = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSize, iconSize)
        drawable.draw(canvas)
        return bitmap
    }
}
