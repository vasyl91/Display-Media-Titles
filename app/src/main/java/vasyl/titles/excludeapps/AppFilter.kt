package vasyl.titles.excludeapps

import android.content.ComponentName
import android.util.Log

abstract class AppFilter {

    abstract fun shouldShowApp(componentName: ComponentName?): Boolean

    companion object {
        private const val DBG = false
        private const val TAG = "AppFilter"

        @JvmStatic
        fun loadByName(className: String?): AppFilter? {
            if (className.isNullOrEmpty()) return null
            if (DBG) Log.d(TAG, "Loading AppFilter: $className")

            return try {
                val cls = Class.forName(className)
                cls.getDeclaredConstructor().newInstance() as AppFilter
            } catch (e: Exception) {
                Log.e(TAG, "Bad AppFilter class", e)
                null
            }
        }
    }
}