package vasyl.titles.colorpicker

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.window.layout.WindowMetricsCalculator
import vasyl.titles.R

class ColorPicker : DialogFragment(), OnSeekBarChangeListener {
    private var colorView: View? = null
    private var alphaSeekBar: SeekBar? = null
    private var redSeekBar: SeekBar? = null
    private var greenSeekBar: SeekBar? = null
    private var blueSeekBar: SeekBar? = null
    private var textView: TextView? = null
    private var hexCode: EditText? = null
    private var alpha = 255
    private var red = 0
    private var green = 0
    private var blue = 0
    private var callback: ColorPickerCallback? = null

    private var withAlpha = false
    private var autoclose = false

    companion object {
        private const val ARG_ALPHA = "alpha"
        private const val ARG_RED = "red"
        private const val ARG_GREEN = "green"
        private const val ARG_BLUE = "blue"
        private const val ARG_WITH_ALPHA = "with_alpha"
        private const val ARG_COLOR = "color"

        fun newInstance(): ColorPicker {
            return ColorPicker()
        }

        fun newInstance(
            @IntRange(from = 0, to = 255) red: Int,
            @IntRange(from = 0, to = 255) green: Int,
            @IntRange(from = 0, to = 255) blue: Int
        ): ColorPicker {
            val args = Bundle().apply {
                putInt(ARG_RED, assertColorValueInRange(red))
                putInt(ARG_GREEN, assertColorValueInRange(green))
                putInt(ARG_BLUE, assertColorValueInRange(blue))
            }
            return ColorPicker().apply { arguments = args }
        }

        fun newInstance(@ColorInt color: Int): ColorPicker {
            val args = Bundle().apply {
                putInt(ARG_COLOR, color)
                putInt(ARG_ALPHA, Color.alpha(color))
                putInt(ARG_RED, Color.red(color))
                putInt(ARG_GREEN, Color.green(color))
                putInt(ARG_BLUE, Color.blue(color))
                putBoolean(ARG_WITH_ALPHA, Color.alpha(color) < 255)
            }
            return ColorPicker().apply { arguments = args }
        }

        fun newInstance(
            @IntRange(from = 0, to = 255) alpha: Int,
            @IntRange(from = 0, to = 255) red: Int,
            @IntRange(from = 0, to = 255) green: Int,
            @IntRange(from = 0, to = 255) blue: Int
        ): ColorPicker {
            val args = Bundle().apply {
                putInt(ARG_ALPHA, assertColorValueInRange(alpha))
                putInt(ARG_RED, assertColorValueInRange(red))
                putInt(ARG_GREEN, assertColorValueInRange(green))
                putInt(ARG_BLUE, assertColorValueInRange(blue))
                putBoolean(ARG_WITH_ALPHA, true)
            }
            return ColorPicker().apply { arguments = args }
        }

        private fun assertColorValueInRange(value: Int): Int {
            return value.coerceIn(0, 255)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Only set callback from context if it wasn't already set programmatically
        if (callback == null) {
            callback = when {
                context is ColorPickerCallback -> context
                parentFragment is ColorPickerCallback -> parentFragment as ColorPickerCallback
                else -> null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            if (args.containsKey(ARG_COLOR)) {
                val color = args.getInt(ARG_COLOR)
                alpha = Color.alpha(color)
                red = Color.red(color)
                green = Color.green(color)
                blue = Color.blue(color)
                withAlpha = Color.alpha(color) < 255
            } else {
                red = args.getInt(ARG_RED, 0)
                green = args.getInt(ARG_GREEN, 0)
                blue = args.getInt(ARG_BLUE, 0)
                alpha = args.getInt(ARG_ALPHA, 255)
                withAlpha = args.getBoolean(ARG_WITH_ALPHA, false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.materialcolorpicker__layout_color_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var textSize = 0
        var padding = 0

        windowSize()?.let { (width, height) ->
            val landscape = context?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
            val deviceWidth = if (landscape) height else width
            textSize = (deviceWidth * 0.015).toInt()
            padding = (deviceWidth * 0.02).toInt()
        }

        // Looked up as plain Views: only the padding is changed, so the layout variants
        // (layout-land / layout-port) may use any container type without a ClassCastException.
        view.findViewById<View>(R.id.pickerContainer)?.updatePadding(
            left = padding,
            right = padding
        )
        view.findViewById<View>(R.id.pickerContainerBottom)?.updatePadding(
            bottom = padding
        )

        colorView = view.findViewById(R.id.colorView)

        alphaSeekBar = view.findViewById(R.id.alphaSeekBar)
        redSeekBar = view.findViewById(R.id.redSeekBar)
        greenSeekBar = view.findViewById(R.id.greenSeekBar)
        blueSeekBar = view.findViewById(R.id.blueSeekBar)
        for (seekBar in listOfNotNull(alphaSeekBar, redSeekBar, greenSeekBar, blueSeekBar)) {
            seekBar.updatePadding(right = padding * 2)
            seekBar.setOnSeekBarChangeListener(this)
        }

        textView = view.findViewById(R.id.textView)
        textView?.textSize = textSize.toFloat()

        hexCode = view.findViewById(R.id.hexCode)
        context?.let {
            hexCode?.setTextColor(ContextCompat.getColor(it, R.color.black))
        }
        hexCode?.textSize = textSize.toFloat()
        hexCode?.filters = arrayOf(InputFilter.LengthFilter(if (withAlpha) 8 else 6))
        hexCode?.setOnEditorActionListener { v, actionId, event ->
            // event is null for IME actions: "Next"/"Go" on the soft keyboard crashed with an NPE.
            val enterPressed = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                updateColorView(v.text.toString())
                context?.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(hexCode?.windowToken, 0)
                true
            } else {
                false
            }
        }

        view.findViewById<Button>(R.id.okColorButton)?.apply {
            this.textSize = textSize.toFloat()
            setOnClickListener { sendColor() }
            updatePadding(left = textSize, right = textSize)
        }

        initUi()
    }

    override fun onStart() {
        super.onStart()
        windowSize()?.let { (width, height) ->
            dialog?.window?.apply {
                setLayout((width * 0.7).toInt(), (height * 0.6).toInt())
                setBackgroundDrawableResource(android.R.color.transparent)
            }
        }
    }

    /**
     * Size of the activity window. On Android 11+ the system bar insets used to be added to the
     * window bounds a second time, so the dialog and its texts were larger than on older versions.
     */
    private fun windowSize(): Pair<Int, Int>? {
        val activity = activity ?: return null
        val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
        return bounds.width() to bounds.height()
    }

    fun enableAutoClose() {
        this.autoclose = true
    }

    fun disableAutoClose() {
        this.autoclose = false
    }

    fun setCallback(listener: ColorPickerCallback?) {
        callback = listener
    }

    private fun initUi() {
        colorView?.setBackgroundColor(getColor())

        alphaSeekBar?.progress = alpha
        redSeekBar?.progress = red
        greenSeekBar?.progress = green
        blueSeekBar?.progress = blue

        if (!withAlpha) {
            alphaSeekBar?.visibility = View.GONE
        }

        hexCode?.setText(
            if (withAlpha) ColorFormatHelper.formatColorValues(alpha, red, green, blue)
            else ColorFormatHelper.formatColorValues(red, green, blue)
        )
    }

    private fun sendColor() {
        callback?.onColorChosen(getColor())
        if (autoclose && isAdded) {
            dismissAllowingStateLoss()
        }
    }

    fun setColor(@ColorInt color: Int) {
        alpha = Color.alpha(color)
        red = Color.red(color)
        green = Color.green(color)
        blue = Color.blue(color)
    }

    private fun updateColorView(input: String) {
        try {
            val color = Color.parseColor("#$input")
            alpha = Color.alpha(color)
            red = Color.red(color)
            green = Color.green(color)
            blue = Color.blue(color)

            colorView?.setBackgroundColor(getColor())

            alphaSeekBar?.progress = alpha
            redSeekBar?.progress = red
            greenSeekBar?.progress = green
            blueSeekBar?.progress = blue
        } catch (ignored: IllegalArgumentException) {
            context?.resources?.getText(R.string.materialcolorpicker__errHex)?.let {
                hexCode?.error = it
            }
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        when (seekBar.id) {
            R.id.alphaSeekBar -> alpha = progress
            R.id.redSeekBar -> red = progress
            R.id.greenSeekBar -> green = progress
            R.id.blueSeekBar -> blue = progress
        }

        colorView?.setBackgroundColor(getColor())

        // Setting the inputText hex color
        hexCode?.setText(
            if (withAlpha) ColorFormatHelper.formatColorValues(alpha, red, green, blue)
            else ColorFormatHelper.formatColorValues(red, green, blue)
        )
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {}

    override fun onStartTrackingTouch(seekBar: SeekBar) {}

    fun getAlpha(): Int {
        return alpha
    }

    fun getRed(): Int {
        return red
    }

    fun getGreen(): Int {
        return green
    }

    fun getBlue(): Int {
        return blue
    }

    fun setAlpha(alpha: Int) {
        this.alpha = alpha
    }

    fun setRed(red: Int) {
        this.red = red
    }

    fun setGreen(green: Int) {
        this.green = green
    }

    fun setBlue(blue: Int) {
        this.blue = blue
    }

    fun setAll(alpha: Int, red: Int, green: Int, blue: Int) {
        this.alpha = alpha
        this.red = red
        this.green = green
        this.blue = blue
    }

    fun setColors(red: Int, green: Int, blue: Int) {
        this.red = red
        this.green = green
        this.blue = blue
    }

    fun getColor(): Int {
        return if (withAlpha) Color.argb(alpha, red, green, blue) else Color.rgb(red, green, blue)
    }

    fun isShowing(): Boolean {
        return dialog?.isShowing == true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear all view references
        colorView = null
        alphaSeekBar = null
        redSeekBar = null
        greenSeekBar = null
        blueSeekBar = null
        textView = null
        hexCode = null
        callback = null
    }
}
