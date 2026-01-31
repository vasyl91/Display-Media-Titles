package vasyl.titles.excludeapps

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import vasyl.titles.DisplayMediaTitles
import vasyl.titles.R

class ExcludeAppsDialog : DialogFragment(), AdapterView.OnItemClickListener {
    
    private var currentAppIcon: ImageView? = null
    private var currentAppName: TextView? = null
    private var mAdapter: AppSelectAdapter? = null
    private var mData: ArrayList<AppInfo>? = null
    private var mGridView: GridView? = null
    private var mItemClickDataListener: ItemClickDataListener? = null
    private var apps: MutableSet<String> = HashSet()
    private var statsPrefs: SharedPreferences? = null

    interface ItemClickDataListener {
        fun onClickData(appInfo: AppInfo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.ExcludeAppsDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        statsPrefs = requireActivity().getSharedPreferences("ExcludeAppsPrefs", MODE_PRIVATE)
        
        // Load apps into a proper mutable HashSet
        val temp = statsPrefs?.getStringSet("exclude_apps", HashSet()) ?: HashSet()
        apps = HashSet()
        apps.addAll(temp)
        
        val view = inflater.inflate(R.layout.dialog_applist, container)
        mData = AllAppsList.data as ArrayList<AppInfo>?
        currentAppIcon = view.findViewById(R.id.current_app_icon)
        currentAppName = view.findViewById(R.id.current_app_name)
        mGridView = view.findViewById(R.id.gridview)
        mAdapter = AppSelectAdapter(mData!!)
        mGridView?.adapter = mAdapter
        mGridView?.onItemClickListener = this
        
        view.setOnClickListener {
            dismiss()
        }
        
        dialog?.window?.requestFeature(1)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.apply {
            setLayout(-1, -1)
        }
        dialog?.setCanceledOnTouchOutside(true)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val allApp = mData?.get(position) ?: return
        
        // Toggle selection
        toggleSelection(allApp.getPackageName())
        
        // Notify adapter to refresh
        mAdapter?.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mGridView?.adapter = null
        mAdapter = null
        currentAppIcon = null
        currentAppName = null
        mGridView = null
        mItemClickDataListener = null  
        mData = null  
    }

    private fun toggleSelection(packageName: String) {
        if (apps.contains(packageName)) {
            apps.remove(packageName)
        } else {
            apps.add(packageName)
        }
        
        // Create a completely new HashSet for saving
        val toSave = HashSet(apps)
        
        // Clear and save
        statsPrefs?.edit()?.apply {
            remove("exclude_apps")
            apply()
        }
        
        statsPrefs?.edit()?.apply {
            putStringSet("exclude_apps", toSave)
            apply()
        }
    }

    fun isShowing(): Boolean {
        return dialog?.isShowing == true
    }

    fun setItemClickDataListener(listener: ItemClickDataListener) {
        mItemClickDataListener = listener
    }

    inner class AppSelectAdapter(private val mData: ArrayList<AppInfo>) : BaseAdapter() {

        override fun getCount(): Int = mData.size

        override fun getItem(position: Int): Any = mData[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: View
            val viewHolder: ViewHolder
            
            if (convertView == null) {
                view = LayoutInflater.from(DisplayMediaTitles.getContext())
                    .inflate(R.layout.item_app_select, null)
                viewHolder = ViewHolder().apply {
                    appIcon = view.findViewById(R.id.app_icon)
                    appName = view.findViewById(R.id.app_name)
                }
                view.tag = viewHolder
            } else {
                view = convertView
                viewHolder = view.tag as ViewHolder
            }
            
            val data = mData[position]
            viewHolder.appIcon?.setImageBitmap(data.iconBitmap)
            viewHolder.appName?.text = data.title
            
            // Set background color based on selection state
            if (apps.contains(data.getPackageName())) {
                view.setBackgroundColor(Color.parseColor("#FC6B03"))
                view.background.alpha = 90
            } else {
                view.setBackgroundColor(Color.TRANSPARENT)
            }
            
            return view
        }
    }

    inner class ViewHolder {
        var appIcon: ImageView? = null
        var appName: TextView? = null
    }
}