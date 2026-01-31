package vasyl.titles.colorpicker

import androidx.annotation.ColorInt

interface ColorPickerCallback {
    fun onColorChosen(@ColorInt color: Int)
}

