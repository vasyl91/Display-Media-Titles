package vasyl.titles

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import vasyl.titles.excludeapps.AllAppsList
import vasyl.titles.excludeapps.AppFilter
import vasyl.titles.excludeapps.AppInfo

//import leakcanary.LeakCanary

class DisplayMediaTitles : Application() {
    
    companion object {
        private lateinit var instance: DisplayMediaTitles

        fun getInstance(): DisplayMediaTitles = instance
        fun getContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setAllApps()
        //LeakCanary.config = LeakCanary.config.copy(
            //retainedVisibleThreshold = 10
        //)
    }

    fun setAllApps() {
        val appFilter = AppFilter.loadByName(getString(R.string.app_filter_class))
        val allAppsList = AllAppsList(appFilter)
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        
        // Use a Set to track unique package names and avoid duplicates
        val addedPackages = HashSet<String>()
        
        for (ri in activities) {
            val packageName = ri.activityInfo.packageName
            
            // Skip launcher apps
            if (packageName.contains("launcher", ignoreCase = true)) {
                continue
            }
            
            // Skip if we've already added this package
            if (addedPackages.contains(packageName)) {
                continue
            }
            
            val info = AppInfo().apply {
                componentName = ComponentName(
                    packageName,
                    ri.activityInfo.name
                )
                title = ri.loadLabel(pm)
                iconBitmap = drawableToBitmap(ri.loadIcon(pm))
                
                // Set the intent so getPackageName() works
                this.intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(
                        packageName,
                        ri.activityInfo.name
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
            }
            
            allAppsList.add(info)
            addedPackages.add(packageName)
        }
    }

    fun drawableToBitmap(drawable: Drawable?): Bitmap {
        drawable ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Define your desired icon size (e.g., 48dp converted to pixels)
        val iconSize = (48 * resources.displayMetrics.density).toInt()
        
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSize, iconSize)
        drawable.draw(canvas)
        return bitmap
    }
}