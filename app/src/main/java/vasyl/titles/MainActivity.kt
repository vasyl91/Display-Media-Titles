package vasyl.titles

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vasyl.titles.colorpicker.ColorPicker
import vasyl.titles.colorpicker.ColorPickerCallback
import vasyl.titles.excludeapps.ExcludeAppsDialog
import vasyl.titles.helpers.InputFilterMinMax
import vasyl.titles.receivers.PhoneListener
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale

@Suppress("UNUSED_PARAMETER", "unused")
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val PREFS = "savedPrefs"
        const val STATE_FLOW_STEP = "permission_flow_step"
        const val STATE_AWAITING_SETTINGS = "awaiting_settings_return"
        const val DIALOG_REOPEN_DELAY_MS = 300L
        const val SCROLL_RESET_DELAY_MS = 400L
        const val BUTTON_COLOR = "#D6C08A"
        const val DEFAULT_TEXT_COLOR = "#FFFFFF"

        const val TYPEFACE_NORMAL = 0
        const val TYPEFACE_BOLD = 1
        const val TYPEFACE_ITALIC = 2
        const val TYPEFACE_TTF = 3
        const val TYPEFACE_OUTLINED = 4

        /** Font files often have no dedicated MIME type, therefore octet-stream is accepted too. */
        val FONT_MIME_TYPES = arrayOf(
            "font/ttf",
            "font/otf",
            "font/sfnt",
            "application/x-font-ttf",
            "application/x-font-truetype",
            "application/x-font-otf",
            "application/font-sfnt",
            "application/vnd.ms-opentype",
            "application/octet-stream",
        )
    }

    /**
     * Steps of the permission flow, requested one after another in this order. Previously the
     * runtime permissions were requested and then four settings screens were opened at the same time
     * (each with FLAG_ACTIVITY_NEW_TASK), so they covered each other.
     */
    private enum class Step { RUNTIME, NOTIFICATION_LISTENER, OVERLAY, ALL_FILES, BATTERY, DONE }

    private lateinit var scrollView: ScrollView

    private lateinit var mAvailableMargin: TextView
    private lateinit var mSetMarginButton: Button
    private lateinit var editMargin: EditText

    private lateinit var mAvailableWidth: TextView
    private lateinit var mSetWidthButton: Button
    private lateinit var editWidth: EditText

    private lateinit var mSetColorButton: Button
    private lateinit var mResetColorButton: Button

    private lateinit var mSetBgColorButton: Button
    private lateinit var mResetBgColorButton: Button

    private lateinit var mUpButton: Button
    private lateinit var mCenterButton: Button
    private lateinit var mDownButton: Button

    private lateinit var mSizeButton: Button
    private lateinit var editSize: EditText

    private lateinit var mNormalButton: Button
    private lateinit var mItalicButton: Button
    private lateinit var mBoldButton: Button
    private lateinit var mNormalOutlinedButton: Button
    private lateinit var mTtfButton: Button
    private lateinit var mTtfUpButton: Button
    private lateinit var mTtfCenterButton: Button
    private lateinit var mTtfDownButton: Button
    private lateinit var typefaceTextView: TextView
    private lateinit var typefaceLinearLayout: ConstraintLayout

    private lateinit var mFytMetaButton: Button
    private lateinit var mFytFileButton: Button

    private lateinit var mDisplayUI: CheckBox
    private lateinit var mAutostart: CheckBox
    private lateinit var mDisplayArtist: CheckBox
    private lateinit var mDisplayTitles: CheckBox
    private lateinit var mExcludeForWidget: CheckBox
    private lateinit var mExcludeForWidgetSummary: TextView

    private lateinit var mPhoneStateButton: Button
    private lateinit var mOutgoingCalls: Button
    private lateinit var mBatteryButton: Button
    private lateinit var mNotificationButton: Button
    private lateinit var mDrawOverAppsButton: Button
    private lateinit var mStoragePermissionsButton: Button
    private lateinit var settings: SharedPreferences

    /** True once all views have been bound (onCreate may finish early). */
    private var uiReady = false

    private var screenWidth: Int = 0
    private var margin: Int = 255
    private var marginString: String = "margin_portrait"
    private var availableMargin: Int = 0
    private var width: Int = 900
    private var widthString: String = "width_portrait"
    private var availableWidth: Int = 0
    private var numUp: Int = 0
    private var numDown: Int = 0
    private var ttfNumUp: Int = 0
    private var ttfNumDown: Int = 0
    private var size: Int = 16
    private var defaultColorR: Int = 255
    private var defaultColorG: Int = 255
    private var defaultColorB: Int = 255
    private var defaultBgColorR: Int = 255
    private var defaultBgColorG: Int = 255
    private var defaultBgColorB: Int = 255
    private var typeface: Int = TYPEFACE_NORMAL
    private var fytData: Int = 1
    private var statusButtonColor = DEFAULT_TEXT_COLOR
    private var statusBgButtonColor = "transparent"
    private var displayUi: Boolean = true
    private var displayArtist: Boolean = true
    private var displayTitles: Boolean = true
    private var excludeForWidget: Boolean = true
    private var autostart: Boolean = false
    private var colorPicker: WeakReference<ColorPicker>? = null
    private var bgColorPicker: WeakReference<ColorPicker>? = null
    private var excludeAppsDialog: WeakReference<ExcludeAppsDialog>? = null

    /** Storage access is only needed for the FYT integration, i.e. not by the "phone" flavor. */
    private val storageRequired = BuildConfig.SYSTEM_BUILD

    private var flowStep: Step? = null
    private var awaitingSettingsReturn = false
    private var pendingSinglePermission: String? = null

    private val buttonColor: Int by lazy { Color.parseColor(BUTTON_COLOR) }

    private val runtimePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PhoneListener.register(applicationContext) // needs READ_PHONE_STATE on Android 12+
            refreshPermissionButtons()
            if (flowStep == Step.RUNTIME) advance(Step.RUNTIME)
        }

    private val singlePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            PhoneListener.register(applicationContext)
            val permission = pendingSinglePermission
            pendingSinglePermission = null
            // No dialog was shown (permanently denied): the app settings are the only way left.
            if (!granted && permission != null && !shouldShowRequestPermissionRationale(permission)) {
                openSettingsScreen(listOf(appDetailsIntent()))
            }
            refreshPermissionButtons()
            finishIfUiHidden()
        }

    /** Storage Access Framework picker: works without any storage permission. */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFont(uri)
        }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settings = getSharedPreferences(PREFS, MODE_PRIVATE)
        displayUi = settings.getBoolean("UI", true)
        if (!displayUi && checkAllPermissions()) {
            // Started directly (e.g. by a shortcut that still points here) with the UI hidden: only
            // wake the app. The regular entry point is the invisible WakeActivity.
            AppWaker.wake(this)
            finish()
            @Suppress("DEPRECATION") // the replacement needs API 34
            overridePendingTransition(0, 0)
            return
        }

        setMarginAndWidth()

        numUp = settings.getInt("up", 0)
        numDown = settings.getInt("down", 0)
        ttfNumUp = settings.getInt("ttf_up", 0)
        ttfNumDown = settings.getInt("ttf_down", 0)
        size = settings.getInt("size", 16)
        statusButtonColor = settings.getString("color", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR
        statusBgButtonColor = settings.getString("bg_color", "transparent") ?: "transparent"
        defaultColorR = settings.getInt("red", 255)
        defaultColorG = settings.getInt("green", 255)
        defaultColorB = settings.getInt("blue", 255)
        defaultBgColorR = settings.getInt("bg_red", 255)
        defaultBgColorG = settings.getInt("bg_green", 255)
        defaultBgColorB = settings.getInt("bg_blue", 255)
        typeface = settings.getInt("typeface", TYPEFACE_NORMAL)
        fytData = settings.getInt("fytData", 1)
        autostart = settings.getBoolean("autostart", false)
        displayArtist = settings.getBoolean("artist_box", true)
        displayTitles = settings.getBoolean("titles_box", true)
        excludeForWidget = settings.getBoolean("exclude_box", true)
        val ttfFile = settings.getString("typeface_ttf", null)?.let(::File)

        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            // Display cutout and keyboard were not taken into account before.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        scrollView.smoothScrollTo(0, 0)

        typefaceTextView = findViewById(R.id.ttf_position)
        typefaceLinearLayout = findViewById(R.id.ttf_buttons)
        if (typeface == TYPEFACE_TTF) {
            if (ttfFile == null || !ttfFile.isFile) {
                setTtfControlsVisible(false)
                typeface = TYPEFACE_NORMAL
                saveInt("typeface", TYPEFACE_NORMAL)
            }
        } else {
            setTtfControlsVisible(false)
        }

        // Caption height
        mUpButton = findViewById(R.id.up_button)
        mCenterButton = findViewById(R.id.center_button)
        mDownButton = findViewById(R.id.down_button)
        if (numUp > 0) {
            mUpButton.text = getString(R.string.up_var, "$numUp")
            mDownButton.setText(R.string.down)
        } else if (numDown > 0) {
            mDownButton.text = getString(R.string.down_var, "$numDown")
            mUpButton.setText(R.string.up)
        } else {
            mUpButton.text = "0"
            mDownButton.text = "0"
        }

        // Font size
        editSize = findViewById(R.id.edit_size)
        editSize.setText(String.format(Locale.US, "%d", size))
        editSize.filters = arrayOf<InputFilter>(InputFilterMinMax("1", "30"))
        editSize.onImeSet { setSizeButton(it) }
        mSizeButton = findViewById(R.id.set_size_button)

        // Color picker
        mSetColorButton = findViewById(R.id.set_color)
        mSetColorButton.setBackgroundColor(parseColorOr(statusButtonColor, Color.WHITE))
        mResetColorButton = findViewById(R.id.reset_color)

        // Background color picker
        mSetBgColorButton = findViewById(R.id.set_bg_color)
        mSetBgColorButton.setBackgroundColor(parseColorOr(statusBgButtonColor, Color.TRANSPARENT))
        mResetBgColorButton = findViewById(R.id.reset_bg_color)

        // Typeface
        mNormalButton = findViewById(R.id.normal_button)
        mItalicButton = findViewById(R.id.italic_button)
        mBoldButton = findViewById(R.id.bold_button)
        mNormalOutlinedButton = findViewById(R.id.normal_outlined_button)
        mTtfButton = findViewById(R.id.ttf_button)
        highlightTypeface(typeface)

        setMarginAndWidthView()

        // ttf height
        mTtfUpButton = findViewById(R.id.ttf_up_button)
        mTtfCenterButton = findViewById(R.id.ttf_center_button)
        mTtfDownButton = findViewById(R.id.ttf_down_button)
        if (ttfNumUp > 0) {
            mTtfUpButton.text = getString(R.string.ttf_up_var, "$ttfNumUp")
            mTtfDownButton.setText(R.string.ttf_down)
        } else if (ttfNumDown > 0) {
            mTtfDownButton.text = getString(R.string.ttf_down_var, "$ttfNumDown")
            mTtfUpButton.setText(R.string.ttf_up)
        } else {
            mTtfUpButton.text = "0"
            mTtfDownButton.text = "0"
        }

        // Fyt title type
        mFytMetaButton = findViewById(R.id.fyt_meta_button)
        mFytFileButton = findViewById(R.id.fyt_file_button)
        if (fytData == 1) {
            mFytMetaButton.setBackgroundColor(Color.GREEN)
            mFytFileButton.setBackgroundColor(buttonColor)
        } else if (fytData == 2) {
            mFytFileButton.setBackgroundColor(Color.GREEN)
            mFytMetaButton.setBackgroundColor(buttonColor)
        }

        // Display UI CheckBox
        mDisplayUI = findViewById(R.id.display_ui_box)
        mDisplayUI.isChecked = displayUi
        findViewById<TextView>(R.id.display_ui_summary).startMarquee()

        // Start app on boot
        mAutostart = findViewById(R.id.autostart_app)
        mAutostart.isChecked = autostart
        findViewById<TextView>(R.id.autostart_app_summary).startMarquee()

        // Display artist
        mDisplayArtist = findViewById(R.id.display_artist_box)
        mDisplayArtist.isChecked = displayArtist

        // Display titles
        mDisplayTitles = findViewById(R.id.display_titles_box)
        mDisplayTitles.isChecked = displayTitles

        // Exclude for widget
        mExcludeForWidget = findViewById(R.id.exclude_for_widget_box)
        mExcludeForWidget.isChecked = excludeForWidget
        mExcludeForWidgetSummary = findViewById(R.id.exclude_summary)
        updateExcludeSummary()

        // Required permissions' buttons
        mPhoneStateButton = findViewById(R.id.read_phone_state_button)
        mOutgoingCalls = findViewById(R.id.outgoing_calls_button)
        mBatteryButton = findViewById(R.id.battery_optimization_button)
        mNotificationButton = findViewById(R.id.notification_listener_button)
        mDrawOverAppsButton = findViewById(R.id.draw_over_apps_button)
        mStoragePermissionsButton = findViewById(R.id.storage_permissions_button)
        if (!storageRequired) mStoragePermissionsButton.visibility = View.GONE

        uiReady = true
        refreshPermissionButtons()

        if (savedInstanceState == null) {
            startPermissionFlow()
        } else {
            flowStep = savedInstanceState.getString(STATE_FLOW_STEP)
                ?.let { name -> Step.entries.firstOrNull { it.name == name } }
            awaitingSettingsReturn = savedInstanceState.getBoolean(STATE_AWAITING_SETTINGS, false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        // Replaces the 50 ms polling loop: permissions can only change while we are in the background.
        refreshPermissionButtons()
        if (awaitingSettingsReturn) {
            awaitingSettingsReturn = false
            val step = flowStep
            if (step != null) {
                advance(step)
                return
            }
        }
        if (flowStep == null) finishIfUiHidden()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        flowStep?.let { outState.putString(STATE_FLOW_STEP, it.name) }
        outState.putBoolean(STATE_AWAITING_SETTINGS, awaitingSettingsReturn)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!uiReady) return
        scrollView.post {
            setMarginAndWidth()
            setMarginAndWidthView()
        }
        if (excludeAppsDialog?.get()?.isShowing() == true) {
            dismissExcludeAppsDialog()
            reopenLater { showExcludeAppsDialog(null) }
        }
        if (colorPicker?.get()?.isShowing() == true) {
            dismissColorPicker()
            reopenLater { showColorPicker(null) }
        }
        if (bgColorPicker?.get()?.isShowing() == true) {
            dismissBgColorPicker()
            reopenLater { showBgColorPicker(null) }
        }
        lifecycleScope.launch {
            delay(SCROLL_RESET_DELAY_MS)
            scrollView.smoothScrollTo(0, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        dismissAllDialogs()
    }

    override fun onDestroy() {
        dismissAllDialogs()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Permission flow
    // ---------------------------------------------------------------------------------------------

    private fun startPermissionFlow() {
        runStep(Step.RUNTIME)
    }

    private fun advance(from: Step) {
        runStep(Step.entries[from.ordinal + 1])
    }

    /** Runs [step]; steps that are already satisfied (or not applicable) are skipped immediately. */
    private fun runStep(step: Step) {
        flowStep = step
        when (step) {
            Step.RUNTIME -> {
                val missing = requiredRuntimePermissions().filterNot { Privileges.isGranted(this, it) }
                if (missing.isEmpty()) {
                    advance(step)
                } else {
                    try {
                        runtimePermissionsLauncher.launch(missing.toTypedArray())
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "No permission controller", e)
                        advance(step)
                    }
                }
            }

            Step.NOTIFICATION_LISTENER ->
                if (Privileges.tryGrantNotificationListenerAccess(this) ||
                    !openSettingsScreen(notificationListenerIntents())
                ) {
                    advance(step)
                }

            Step.OVERLAY ->
                if (Privileges.canDrawOverlays(this) || !openSettingsScreen(overlayIntents())) advance(step)

            Step.ALL_FILES ->
                if (!storageRequired || Privileges.isStorageAccessGranted(this) ||
                    !openSettingsScreen(allFilesAccessIntents())
                ) {
                    advance(step)
                }

            Step.BATTERY ->
                if (Privileges.tryWhitelistFromBatteryOptimizations(this) ||
                    !openSettingsScreen(batteryOptimizationIntents())
                ) {
                    advance(step)
                }

            Step.DONE -> {
                flowStep = null
                refreshPermissionButtons()
                finishIfUiHidden()
            }
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        if (storageRequired) addAll(Privileges.legacyStoragePermissions())
    }

    /**
     * Opens the first settings screen that exists on this device. The flow continues in onResume
     * when the user comes back (results of settings screens are unreliable across OEMs).
     * @return false if none of the intents could be started
     */
    private fun openSettingsScreen(intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY))
                awaitingSettingsReturn = true
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity for ${intent.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open ${intent.action}", e)
            }
        }
        return false
    }

    private fun packageUri(): Uri = Uri.fromParts("package", packageName, null)

    private fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun notificationListenerIntents(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Opens the switch for this app directly.
            add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    Privileges.notificationListenerComponent(this@MainActivity).flattenToString()
                )
            )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun overlayIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    )

    private fun allFilesAccessIntents(): List<Intent> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri()),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        } else {
            emptyList()
        }

    @SuppressLint("BatteryLife")
    private fun batteryOptimizationIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    private fun checkAllPermissions(): Boolean =
        Privileges.allRequiredPermissionsGranted(this, storageRequired)

    private fun finishIfUiHidden() {
        if (!displayUi && !isFinishing && checkAllPermissions()) finish()
    }

    private fun refreshPermissionButtons() {
        if (!uiReady) return
        setPermissionButton(mPhoneStateButton, Privileges.isGranted(this, Manifest.permission.READ_PHONE_STATE))
        setPermissionButton(mOutgoingCalls, Privileges.isGranted(this, Manifest.permission.READ_CALL_LOG))
        setPermissionButton(mBatteryButton, Privileges.isIgnoringBatteryOptimizations(this))
        setPermissionButton(mNotificationButton, Privileges.isNotificationListenerEnabled(this))
        setPermissionButton(mDrawOverAppsButton, Privileges.canDrawOverlays(this))
        if (storageRequired) {
            setPermissionButton(mStoragePermissionsButton, Privileges.isStorageAccessGranted(this))
        }
    }

    /** Green and disabled when granted; red and clickable again if it was revoked meanwhile. */
    private fun setPermissionButton(button: Button, granted: Boolean) {
        button.setBackgroundColor(if (granted) Color.GREEN else Color.RED)
        button.isEnabled = !granted
    }

    private fun requestSinglePermission(permission: String) {
        if (Privileges.isGranted(this, permission)) return
        pendingSinglePermission = permission
        try {
            singlePermissionLauncher.launch(permission)
        } catch (e: ActivityNotFoundException) {
            pendingSinglePermission = null
            openSettingsScreen(listOf(appDetailsIntent()))
        }
    }

    fun phoneStateButton(v: View?) {
        requestSinglePermission(Manifest.permission.READ_PHONE_STATE)
    }

    fun outgoingCallsButton(v: View?) {
        requestSinglePermission(Manifest.permission.READ_CALL_LOG)
    }

    fun batteryButton(v: View?) {
        checkBatteryPermission()
    }

    fun notificationButton(v: View?) {
        if (Privileges.tryGrantNotificationListenerAccess(this)) {
            refreshPermissionButtons()
        } else {
            openSettingsScreen(notificationListenerIntents())
        }
    }

    fun drawOverAppsButton(v: View?) {
        if (!Privileges.canDrawOverlays(this)) openSettingsScreen(overlayIntents())
    }

    fun storagePermissionsButton(v: View?) {
        if (!storageRequired || Privileges.isStorageAccessGranted(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openSettingsScreen(allFilesAccessIntents())
        } else {
            val missing = Privileges.legacyStoragePermissions().filterNot { Privileges.isGranted(this, it) }
            if (missing.isNotEmpty()) runtimePermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    /** Do not optimize battery permission. */
    fun checkBatteryPermission() {
        if (Privileges.tryWhitelistFromBatteryOptimizations(this)) {
            refreshPermissionButtons()
        } else {
            openSettingsScreen(batteryOptimizationIntents())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Margin / width
    // ---------------------------------------------------------------------------------------------

    private fun setMarginAndWidth() {
        val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        screenWidth = bounds.width()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        marginString = if (landscape) "margin_landscape" else "margin_portrait"
        widthString = if (landscape) "width_landscape" else "width_portrait"
        val marginPercentage = (screenWidth * 0.1275).toInt()
        val widthPercentage = (screenWidth * 0.45).toInt()
        settings.edit {
            putInt("marginPercentage", marginPercentage)
            putInt("widthPercentage", widthPercentage)
        }
        margin = settings.getInt(marginString, marginPercentage)
        width = settings.getInt(widthString, widthPercentage)
    }

    private fun setMarginAndWidthView() {
        // Caption margin, available margin and according button
        availableMargin = (screenWidth - width).coerceAtLeast(1)
        editMargin = findViewById(R.id.edit_margin)
        // Text before the filter: filters also apply to setText(), so a stored value outside the
        // current range (e.g. after a rotation) used to leave the field empty.
        editMargin.filters = emptyArray()
        editMargin.setText(String.format(Locale.US, "%d", margin))
        editMargin.filters = arrayOf<InputFilter>(InputFilterMinMax("1", availableMargin.toString()))
        editMargin.onImeSet { setMarginButton(it) }
        mAvailableMargin = findViewById(R.id.available_margin)
        mAvailableMargin.text = getString(R.string.available_margin, " ", "$availableMargin")
        mSetMarginButton = findViewById(R.id.set_margin_button)

        // Caption width, available width and according button
        availableWidth = (screenWidth - margin).coerceAtLeast(1)
        editWidth = findViewById(R.id.edit_width)
        editWidth.filters = emptyArray()
        editWidth.setText(String.format(Locale.US, "%d", width))
        editWidth.filters = arrayOf<InputFilter>(InputFilterMinMax("1", availableWidth.toString()))
        editWidth.onImeSet { setWidthButton(it) }
        mAvailableWidth = findViewById(R.id.available_width)
        mAvailableWidth.text = getString(R.string.available_width, " ", "$availableWidth")
        mSetWidthButton = findViewById(R.id.set_width_button)
    }

    fun setMarginButton(v: View?) {
        hideKeyboard(v)
        // An empty field crashed the app with NumberFormatException.
        val value = editMargin.text.toString().toIntOrNull() ?: return
        if (value < 0 || value > availableMargin) return
        margin = value
        marginString = if (isLandscape()) "margin_landscape" else "margin_portrait"
        saveInt(marginString, margin)
        availableWidth = (screenWidth - margin).coerceAtLeast(1)
        mAvailableWidth.text = getString(R.string.available_width, " ", "$availableWidth")
        editWidth.filters = arrayOf<InputFilter>(InputFilterMinMax("1", availableWidth.toString()))
        showInfoToast(getString(R.string.toast_margin_set))
        previewAppearance()
    }

    fun setWidthButton(v: View?) {
        hideKeyboard(v)
        val value = editWidth.text.toString().toIntOrNull() ?: return
        if (value < 1 || value > availableWidth) return
        width = value
        widthString = if (isLandscape()) "width_landscape" else "width_portrait"
        saveInt(widthString, width)
        availableMargin = (screenWidth - width).coerceAtLeast(1)
        mAvailableMargin.text = getString(R.string.available_margin, " ", "$availableMargin")
        editMargin.filters = arrayOf<InputFilter>(InputFilterMinMax("1", availableMargin.toString()))
        showInfoToast(getString(R.string.toast_width_set))
        previewAppearance()
    }

    // ---------------------------------------------------------------------------------------------
    // Colors
    // ---------------------------------------------------------------------------------------------

    fun showColorPicker(v: View?) {
        if (colorPicker?.get()?.isAdded == true || !canShowDialog()) return

        val picker = ColorPicker.newInstance(defaultColorR, defaultColorG, defaultColorB)
        colorPicker = WeakReference(picker)
        picker.enableAutoClose()
        picker.setCallback(object : ColorPickerCallback {
            override fun onColorChosen(color: Int) {
                defaultColorR = Color.red(color)
                defaultColorG = Color.green(color)
                defaultColorB = Color.blue(color)
                statusButtonColor = formatRgb(color)
                settings.edit {
                    putString("color", statusButtonColor)
                    putInt("red", defaultColorR)
                    putInt("green", defaultColorG)
                    putInt("blue", defaultColorB)
                }
                mSetColorButton.setBackgroundColor(color)
                showInfoToast(getString(R.string.toast_text_color_set))
                previewAppearance()
            }
        })
        picker.show(supportFragmentManager, "ColorPicker")
    }

    fun dismissColorPicker() {
        colorPicker?.get()?.dismissSafely()
        colorPicker = null
    }

    fun resetColorButton(v: View?) {
        statusButtonColor = DEFAULT_TEXT_COLOR
        mSetColorButton.setBackgroundColor(Color.WHITE)
        defaultColorR = 255
        defaultColorG = 255
        defaultColorB = 255
        settings.edit {
            putString("color", statusButtonColor)
            putInt("red", 255)
            putInt("green", 255)
            putInt("blue", 255)
        }
        previewAppearance()
    }

    fun showBgColorPicker(v: View?) {
        // The background picker used to be stored in colorPicker, so it could never be dismissed
        // and it blocked the text color picker.
        if (bgColorPicker?.get()?.isAdded == true || !canShowDialog()) return

        val picker = ColorPicker.newInstance(defaultBgColorR, defaultBgColorG, defaultBgColorB)
        bgColorPicker = WeakReference(picker)
        picker.enableAutoClose()
        picker.setCallback(object : ColorPickerCallback {
            override fun onColorChosen(color: Int) {
                defaultBgColorR = Color.red(color)
                defaultBgColorG = Color.green(color)
                defaultBgColorB = Color.blue(color)
                statusBgButtonColor = formatRgb(color)
                settings.edit {
                    putString("bg_color", statusBgButtonColor)
                    putInt("bg_red", defaultBgColorR)
                    putInt("bg_green", defaultBgColorG)
                    putInt("bg_blue", defaultBgColorB)
                }
                mSetBgColorButton.setBackgroundColor(color)
                showInfoToast(getString(R.string.toast_bg_color_set))
                previewAppearance()
            }
        })
        picker.show(supportFragmentManager, "BgColorPicker")
    }

    fun dismissBgColorPicker() {
        bgColorPicker?.get()?.dismissSafely()
        bgColorPicker = null
    }

    fun resetBgButton(v: View?) {
        statusBgButtonColor = "transparent"
        mSetBgColorButton.setBackgroundColor(Color.TRANSPARENT)
        defaultBgColorR = 255
        defaultBgColorG = 255
        defaultBgColorB = 255
        settings.edit {
            putString("bg_color", "transparent")
            putInt("bg_red", 255)
            putInt("bg_green", 255)
            putInt("bg_blue", 255)
        }
        previewAppearance()
    }

    // ---------------------------------------------------------------------------------------------
    // Caption position / size
    // ---------------------------------------------------------------------------------------------

    fun upButton(v: View?) {
        if (settings.getInt("up", 0) != 0) {
            numUp = settings.getInt("up", 0)
        }
        if (numDown > 0) {
            numDown--
            mDownButton.text = getString(R.string.down_var, "$numDown")
        } else if (numUp < 100) {
            numUp++
            mUpButton.text = getString(R.string.up_var, "$numUp")
            mDownButton.setText(R.string.down)
        }
        settings.edit {
            putInt("up", numUp)
            putInt("down", numDown)
        }
        previewAppearance()
    }

    fun centerButton(v: View?) {
        numUp = 0
        numDown = 0
        settings.edit {
            putInt("up", numUp)
            putInt("down", numDown)
        }
        mUpButton.text = "0"
        mDownButton.text = "0"
        previewAppearance()
    }

    fun downButton(v: View?) {
        if (settings.getInt("down", 0) != 0) {
            numDown = settings.getInt("down", 0)
        }
        if (numUp > 0) {
            numUp--
            mUpButton.text = getString(R.string.up_var, "$numUp")
        } else if (numDown < 100) {
            numDown++
            mDownButton.text = getString(R.string.down_var, "$numDown")
            mUpButton.setText(R.string.up)
        }
        settings.edit {
            putInt("up", numUp)
            putInt("down", numDown)
        }
        previewAppearance()
    }

    fun setSizeButton(v: View?) {
        hideKeyboard(v)
        val value = editSize.text.toString().toIntOrNull() ?: return
        if (value !in 1..30) return
        size = value
        saveInt("size", size)
        showInfoToast(getString(R.string.toast_size_set))
        previewAppearance()
    }

    // ---------------------------------------------------------------------------------------------
    // Typeface
    // ---------------------------------------------------------------------------------------------

    fun normalButton(v: View?) = selectBuiltInTypeface(TYPEFACE_NORMAL)

    fun italicButton(v: View?) = selectBuiltInTypeface(TYPEFACE_ITALIC)

    fun boldButton(v: View?) = selectBuiltInTypeface(TYPEFACE_BOLD)

    fun normalOutlinedButton(v: View?) = selectBuiltInTypeface(TYPEFACE_OUTLINED)

    private fun selectBuiltInTypeface(value: Int) {
        typeface = value
        saveInt("typeface", value)
        highlightTypeface(value)
        setTtfControlsVisible(false)
        previewAppearance()
    }

    /**
     * Picks a font with the system file picker and copies it into the app's private storage, so the
     * overlay no longer depends on an external storage path (and on storage permissions).
     */
    fun ttfButton(v: View?) {
        try {
            fontPicker.launch(FONT_MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No document picker", e)
            showInfoToast(getString(R.string.toast_no_file_picker))
        }
    }

    private fun importFont(uri: Uri) {
        lifecycleScope.launch {
            // Once started, the import is finished and saved even if the activity is closed
            // meanwhile. The previous font is deleted only after the new path has been committed;
            // it used to be deleted first, so a cancelled import left the setting pointing to a
            // deleted file and the overlay fell back to the default typeface.
            val file = withContext(NonCancellable + Dispatchers.IO) {
                copyFontToPrivateStorage(uri)?.also { font ->
                    settings.edit(commit = true) {
                        putInt("typeface", TYPEFACE_TTF)
                        putString("typeface_ttf", font.absolutePath)
                    }
                    font.parentFile?.listFiles()?.forEach { if (it != font) it.delete() }
                }
            }
            if (file == null) {
                if (isActive) showInfoToast(getString(R.string.toast_invalid_font))
                return@launch
            }
            typeface = TYPEFACE_TTF
            previewAppearance()
            if (isActive && uiReady) {
                setTtfControlsVisible(true)
                highlightTypeface(TYPEFACE_TTF)
            }
        }
    }

    /** Runs on Dispatchers.IO. @return the copied and validated font file or null. */
    private fun copyFontToPrivateStorage(uri: Uri): File? {
        val dir = File(filesDir, "fonts")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "font_${System.currentTimeMillis()}.ttf")
        try {
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (copied <= 0L || !hasFontSignature(target) || Typeface.Builder(target).build() == null) {
                target.delete()
                return null
            }
            return target
        } catch (e: IOException) {
            Log.w(TAG, "Cannot import font", e)
        } catch (e: RuntimeException) { // SecurityException, IllegalArgumentException
            Log.w(TAG, "Cannot import font", e)
        }
        target.delete()
        return null
    }

    private fun hasFontSignature(file: File): Boolean {
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) return false
        val tag = String(header, Charsets.ISO_8859_1)
        val trueType = header[0] == 0.toByte() && header[1] == 1.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte()
        return trueType || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    private fun highlightTypeface(value: Int) {
        typefaceButtons(
            normal = if (value == TYPEFACE_NORMAL) Color.GREEN else buttonColor,
            italic = if (value == TYPEFACE_ITALIC) Color.GREEN else buttonColor,
            bold = if (value == TYPEFACE_BOLD) Color.GREEN else buttonColor,
            outlined = if (value == TYPEFACE_OUTLINED) Color.GREEN else buttonColor,
            ttf = if (value == TYPEFACE_TTF) Color.GREEN else buttonColor,
        )
    }

    private fun typefaceButtons(normal: Int, italic: Int, bold: Int, outlined: Int, ttf: Int) {
        mNormalButton.setBackgroundColor(normal)
        mItalicButton.setBackgroundColor(italic)
        mBoldButton.setBackgroundColor(bold)
        mNormalOutlinedButton.setBackgroundColor(outlined)
        mTtfButton.setBackgroundColor(ttf)
    }

    private fun setTtfControlsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        typefaceTextView.visibility = visibility
        typefaceLinearLayout.visibility = visibility
    }

    fun ttfUpButton(v: View?) {
        if (settings.getInt("ttf_up", 0) != 0) {
            ttfNumUp = settings.getInt("ttf_up", 0)
        }
        if (ttfNumDown > 0) {
            ttfNumDown--
            mTtfDownButton.text = getString(R.string.ttf_down_var, "$ttfNumDown")
        } else if (ttfNumUp < 100) {
            ttfNumUp++
            mTtfUpButton.text = getString(R.string.ttf_up_var, "$ttfNumUp")
            mTtfDownButton.setText(R.string.ttf_down)
        }
        settings.edit {
            putInt("ttf_up", ttfNumUp)
            putInt("ttf_down", ttfNumDown)
        }
        previewAppearance()
    }

    fun ttfCenterButton(v: View?) {
        ttfNumUp = 0
        ttfNumDown = 0
        settings.edit {
            putInt("ttf_up", ttfNumUp)
            putInt("ttf_down", ttfNumDown)
        }
        mTtfUpButton.text = "0"
        mTtfDownButton.text = "0"
        previewAppearance()
    }

    fun ttfDownButton(v: View?) {
        if (settings.getInt("ttf_down", 0) != 0) {
            ttfNumDown = settings.getInt("ttf_down", 0)
        }
        if (ttfNumUp > 0) {
            ttfNumUp--
            mTtfUpButton.text = getString(R.string.ttf_up_var, "$ttfNumUp")
        } else if (ttfNumDown < 100) {
            ttfNumDown++
            mTtfDownButton.text = getString(R.string.ttf_down_var, "$ttfNumDown")
            mTtfUpButton.setText(R.string.ttf_up)
        }
        settings.edit {
            putInt("ttf_up", ttfNumUp)
            putInt("ttf_down", ttfNumDown)
        }
        previewAppearance()
    }

    // ---------------------------------------------------------------------------------------------
    // FYT / check boxes
    // ---------------------------------------------------------------------------------------------

    fun setFytMetaButton(v: View?) {
        fytData = 1
        saveInt("fytData", 1)
        mFytMetaButton.setBackgroundColor(Color.GREEN)
        mFytFileButton.setBackgroundColor(buttonColor)
        applyDisplaySettings()
    }

    fun setFytFileButton(v: View?) {
        fytData = 2
        saveInt("fytData", 2)
        mFytFileButton.setBackgroundColor(Color.GREEN)
        mFytMetaButton.setBackgroundColor(buttonColor)
        applyDisplaySettings()
    }

    fun setDisplayUI(v: View?) {
        displayUi = mDisplayUI.isChecked
        settings.edit { putBoolean("UI", displayUi) }
    }

    fun setAutostart(v: View?) {
        autostart = mAutostart.isChecked
        settings.edit { putBoolean("autostart", autostart) }
    }

    fun setDisplayArtist(v: View?) {
        displayArtist = mDisplayArtist.isChecked
        settings.edit { putBoolean("artist_box", displayArtist) }
        applyDisplaySettings()
    }

    fun setDisplayTitles(v: View?) {
        displayTitles = mDisplayTitles.isChecked
        settings.edit { putBoolean("titles_box", displayTitles) }
        applyDisplaySettings()
    }

    fun setExcludeForWidget(v: View?) {
        excludeForWidget = mExcludeForWidget.isChecked
        settings.edit { putBoolean("exclude_box", excludeForWidget) }
        updateExcludeSummary()
    }

    private fun updateExcludeSummary() {
        if (excludeForWidget) {
            mExcludeForWidgetSummary.visibility = View.VISIBLE
            mExcludeForWidgetSummary.startMarquee()
        } else {
            mExcludeForWidgetSummary.visibility = View.INVISIBLE
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------------------------------------

    fun showExcludeAppsDialog(v: View?) {
        if (excludeAppsDialog?.get()?.isAdded == true || !canShowDialog()) return
        val dialog = ExcludeAppsDialog()
        excludeAppsDialog = WeakReference(dialog)
        dialog.show(supportFragmentManager, "exclude_apps_dialog")
    }

    fun dismissExcludeAppsDialog() {
        excludeAppsDialog?.get()?.dismissSafely()
        excludeAppsDialog = null
    }

    private fun dismissAllDialogs() {
        excludeAppsDialog?.get()?.dismissSafely(executePending = false)
        excludeAppsDialog = null
        colorPicker?.get()?.dismissSafely(executePending = false)
        colorPicker = null
        bgColorPicker?.get()?.dismissSafely(executePending = false)
        bgColorPicker = null
    }

    /** DialogFragment.show() throws after onSaveInstanceState. */
    private fun canShowDialog(): Boolean = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved

    private fun reopenLater(action: () -> Unit) {
        lifecycleScope.launch {
            delay(DIALOG_REOPEN_DELAY_MS)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action()
        }
    }

    private fun androidx.fragment.app.DialogFragment.dismissSafely(executePending: Boolean = true) {
        if (!isAdded) return
        dismissAllowingStateLoss()
        if (executePending && !supportFragmentManager.isStateSaved) {
            try {
                supportFragmentManager.executePendingTransactions()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "executePendingTransactions failed", e)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Appearance setting changed: the title on screen shows it immediately; without one, a scrolling
     * preview appears for three seconds (it used to require a pause/play or a track change).
     */
    private fun previewAppearance() {
        NotificationListener.previewAppearance()
    }

    /** Display setting changed (titles, artist, FYT title source): applied to the current player now. */
    private fun applyDisplaySettings() {
        NotificationListener.refreshNow()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun saveInt(key: String, value: Int) {
        settings.edit { putInt(key, value) }
    }

    private fun hideKeyboard(v: View?) {
        val token = v?.windowToken ?: window.decorView.windowToken
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(token, 0)
    }

    @SuppressLint("InflateParams")
    private fun showInfoToast(message: String) {
        val layout = layoutInflater.inflate(R.layout.toast, null)
        layout.findViewById<TextView>(R.id.text)?.text = message
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        @Suppress("DEPRECATION")
        toast.view = layout
        toast.show()
    }

    /**
     * Runs [action] for the keyboard's "Set" key (and Enter on a hardware keyboard).
     *
     * The layout gives the key a custom label but no android:imeActionId, so keyboards sent the
     * action id 0 instead of IME_ACTION_DONE: nothing happened, and TextView's fallback turned it
     * into an Enter key that moved the focus to the next view (which sometimes switched the numeric
     * keyboard to the text one). The label is now bound to IME_ACTION_DONE, and the event is
     * consumed so the focus stays in the field.
     */
    private fun EditText.onImeSet(action: (View) -> Unit) {
        setImeActionLabel(getString(R.string.ime_action_set), EditorInfo.IME_ACTION_DONE)
        setOnEditorActionListener { v, actionId, event ->
            when {
                // Enter as key events: the listener is called for DOWN and UP, act once.
                event != null && event.keyCode == KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) action(v)
                    true
                }
                // The action key; 0 (IME_ACTION_UNSPECIFIED) is what keyboards sent before.
                actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_UNSPECIFIED -> {
                    action(v)
                    true
                }
                else -> false
            }
        }
    }

    private fun TextView.startMarquee() {
        isSelected = true
        setSingleLine(true)
    }

    private fun formatRgb(color: Int): String = String.format(Locale.US, "#%06X", 0xFFFFFF and color)

    private fun parseColorOr(value: String, fallback: Int): Int {
        if (value.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
        return try {
            Color.parseColor(value)
        } catch (e: IllegalArgumentException) {
            fallback
        }
    }
}
