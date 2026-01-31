package vasyl.titles.excludeapps

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap

class AppInfo : ItemInfo() {

    var componentName: ComponentName? = null
    var flags: Int = 0
    var iconBitmap: Bitmap? = null
    var intent: Intent? = null

    init {
        flags = 0
        itemType = 1
    }

    override fun toString(): String {
        return "ApplicationInfo(" +
                "title=${title.toString()} " +
                "id=$id " +
                "type=$itemType " +
                "container=$container " +
                "screen=$screenId " +
                "cellX=$cellX " +
                "cellY=$cellY " +
                "spanX=$spanX " +
                "spanY=$spanY " +
                "dropPos=$dropPos" +
                ")"
    }

    fun getPackageName(): String {
        intent?.let { intent ->
            var packageName = intent.getPackage()
            if (packageName == null && intent.component != null) {
                packageName = intent.component?.packageName
            }
            if (packageName != null) return packageName
        }
        return ""
    }
}