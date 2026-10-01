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
        itemType = 1
    }

    override fun toString(): String {
        return "ApplicationInfo(" +
            "title=$title " +
            "id=$id " +
            "type=$itemType " +
            "container=$container " +
            "screen=$screenId " +
            "cellX=$cellX " +
            "cellY=$cellY " +
            "spanX=$spanX " +
            "spanY=$spanY " +
            "dropPos=${dropPos?.contentToString()}" +
            ")"
    }

    /** Package of the launch intent, falling back to the component name; "" if unknown. */
    fun getPackageName(): String =
        intent?.`package` ?: intent?.component?.packageName ?: componentName?.packageName ?: ""
}
