package vasyl.titles.excludeapps

import android.content.ComponentName

class AllAppsList(private val appFilter: AppFilter?) {

    companion object {
        const val DEFAULT_APPLICATIONS_NUMBER = 42
        val data: MutableList<AppInfo> = ArrayList(DEFAULT_APPLICATIONS_NUMBER)
    }

    val added: MutableList<AppInfo> = ArrayList(DEFAULT_APPLICATIONS_NUMBER)
    val removed: MutableList<AppInfo> = ArrayList()
    val modified: MutableList<AppInfo> = ArrayList()

    fun add(info: AppInfo) {
        if (appFilter != null && !appFilter.shouldShowApp(info.componentName)) {
            return
        }
        if (findActivity(data, info.componentName)) {
            return
        }
        data.add(info)
        added.add(info)
    }

    fun clear() {
        data.clear()
        added.clear()
        removed.clear()
        modified.clear()
    }

    fun size(): Int = data.size

    private fun findActivity(apps: List<AppInfo>, component: ComponentName?): Boolean {
        return apps.any { it.componentName == component }
    }
}
