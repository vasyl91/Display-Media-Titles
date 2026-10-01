package vasyl.titles.excludeapps

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import vasyl.titles.DisplayMediaTitles
import vasyl.titles.NotificationListener
import vasyl.titles.R

class ExcludeAppsDialog : DialogFragment(), AdapterView.OnItemClickListener {

    private companion object {
        const val PREFS = "ExcludeAppsPrefs"
        const val KEY_EXCLUDED = "exclude_apps"

        /** #FC6B03 at alpha 90 (previously a ColorDrawable whose alpha was changed afterwards). */
        val SELECTED_COLOR = Color.argb(90, 0xFC, 0x6B, 0x03)
    }

    private var currentAppIcon: ImageView? = null
    private var currentAppName: TextView? = null
    private var mAdapter: AppSelectAdapter? = null
    private var mGridView: GridView? = null
    private var mItemClickDataListener: ItemClickDataListener? = null
    private val apps: MutableSet<String> = HashSet()
    private var statsPrefs: SharedPreferences? = null

    interface ItemClickDataListener {
        fun onClickData(appInfo: AppInfo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ExcludeAppsDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            // Must happen before any content is set (was window.requestFeature(1) in onCreateView).
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        statsPrefs = prefs
        // Copy: the set returned by SharedPreferences must never be modified.
        apps.clear()
        prefs.getStringSet(KEY_EXCLUDED, null)?.let { apps.addAll(it) }

        val view = inflater.inflate(R.layout.dialog_applist, container, false)
        currentAppIcon = view.findViewById(R.id.current_app_icon)
        currentAppName = view.findViewById(R.id.current_app_name)
        val adapter = AppSelectAdapter()
        mAdapter = adapter
        mGridView = view.findViewById<GridView>(R.id.gridview)?.also { grid ->
            grid.adapter = adapter
            grid.onItemClickListener = this
        }

        view.setOnClickListener {
            dismiss()
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.setCanceledOnTouchOutside(true)

        // Launcher apps are loaded in the background at startup: show them as soon as they arrive.
        viewLifecycleOwner.lifecycleScope.launch {
            AllAppsList.apps.collect { list -> mAdapter?.submit(list) }
        }
        if (AllAppsList.apps.value.isEmpty()) DisplayMediaTitles.getInstance().setAllAppsAsync()
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val app = mAdapter?.getItem(position) as? AppInfo ?: return

        // Toggle selection
        toggleSelection(app.getPackageName())

        // Notify adapter to refresh
        mAdapter?.notifyDataSetChanged()
        mItemClickDataListener?.onClickData(app)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mGridView?.adapter = null
        mAdapter = null
        currentAppIcon = null
        currentAppName = null
        mGridView = null
        mItemClickDataListener = null
    }

    private fun toggleSelection(packageName: String) {
        if (packageName.isEmpty()) return
        if (!apps.add(packageName)) apps.remove(packageName)

        // One write with a new set instance (it was removed and written again in two edits).
        statsPrefs?.edit { putStringSet(KEY_EXCLUDED, HashSet(apps)) }

        // Apply the exclusion to the overlay right away.
        NotificationListener.refreshNow()
    }

    fun isShowing(): Boolean {
        return dialog?.isShowing == true
    }

    fun setItemClickDataListener(listener: ItemClickDataListener) {
        mItemClickDataListener = listener
    }

    private inner class AppSelectAdapter : BaseAdapter() {

        private var items: List<AppInfo> = emptyList()

        fun submit(newItems: List<AppInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        @SuppressLint("InflateParams")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: View
            val viewHolder: ViewHolder

            if (convertView == null) {
                // Same inflation as before (application theme, no parent), so the items look the same.
                view = LayoutInflater.from(DisplayMediaTitles.getContext())
                    .inflate(R.layout.item_app_select, null)
                viewHolder = ViewHolder(
                    view.findViewById(R.id.app_icon),
                    view.findViewById(R.id.app_name)
                )
                view.tag = viewHolder
            } else {
                view = convertView
                viewHolder = view.tag as ViewHolder
            }

            val data = items[position]
            viewHolder.appIcon?.setImageBitmap(data.iconBitmap)
            viewHolder.appName?.text = data.title

            // Set background color based on selection state
            view.setBackgroundColor(if (apps.contains(data.getPackageName())) SELECTED_COLOR else Color.TRANSPARENT)

            return view
        }
    }

    private class ViewHolder(val appIcon: ImageView?, val appName: TextView?)
}
