package vasyl.titles.excludeapps

import android.content.ComponentName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Launcher apps offered in [ExcludeAppsDialog]. All mutations happen on the main thread. */
class AllAppsList(private val appFilter: AppFilter?) {

    companion object {
        const val DEFAULT_APPLICATIONS_NUMBER = 42
        val data: MutableList<AppInfo> = ArrayList(DEFAULT_APPLICATIONS_NUMBER)

        private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())

        /**
         * Immutable snapshots of [data]. The list is now loaded in the background at startup, so the
         * dialog observes this instead of reading [data] once (it could open before loading ended).
         */
        val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    }

    val added: MutableList<AppInfo> = ArrayList(DEFAULT_APPLICATIONS_NUMBER)
    val removed: MutableList<AppInfo> = ArrayList()
    val modified: MutableList<AppInfo> = ArrayList()

    fun add(info: AppInfo) {
        if (addInternal(info)) publish()
    }

    /** Replaces the whole list (uninstalled apps used to stay until restart) and publishes once. */
    fun replaceAll(infos: Collection<AppInfo>) {
        data.clear()
        added.clear()
        removed.clear()
        modified.clear()
        infos.forEach { addInternal(it) }
        publish()
    }

    fun clear() {
        data.clear()
        added.clear()
        removed.clear()
        modified.clear()
        publish()
    }

    fun size(): Int = data.size

    private fun addInternal(info: AppInfo): Boolean {
        if (appFilter != null && !appFilter.shouldShowApp(info.componentName)) {
            return false
        }
        if (findActivity(data, info.componentName)) {
            return false
        }
        data.add(info)
        added.add(info)
        return true
    }

    private fun publish() {
        _apps.value = data.toList()
    }

    private fun findActivity(apps: List<AppInfo>, component: ComponentName?): Boolean {
        return apps.any { it.componentName == component }
    }
}
