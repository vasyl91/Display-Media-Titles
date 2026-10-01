package vasyl.titles.colorpicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.Dimension
import androidx.appcompat.widget.AppCompatSeekBar
import vasyl.titles.R

/** SeekBar that draws its value (or a fixed text) above the thumb. */
internal class MaterialColorPickerTextSeekBar : AppCompatSeekBar {

    private val textPaint = Paint(Paint.LINEAR_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textRect = Rect()

    @ColorInt
    private var textColor = 0

    @Dimension(unit = 2)
    private var textSize = 0f

    private var text: String? = null

    constructor(context: Context) : super(context) {
        setup(null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setup(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setup(attrs)
    }

    private fun setup(attrs: AttributeSet?) {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.MaterialColorPickerTextSeekBar)
            try {
                textColor = typedArray.getColor(
                    R.styleable.MaterialColorPickerTextSeekBar_android_textColorHint,
                    -0x1000000
                )
                textSize = typedArray.getDimension(
                    R.styleable.MaterialColorPickerTextSeekBar_android_textSize,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
                )
                text = typedArray.getString(R.styleable.MaterialColorPickerTextSeekBar_android_text)
            } finally {
                typedArray.recycle()
            }
        }

        textPaint.color = textColor
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = textSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.getTextBounds("255", 0, 3, textRect)

        setPadding(
            paddingLeft,
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                (0.6 * textRect.height()).toFloat(),
                resources.displayMetrics
            ).toInt(),
            paddingRight,
            paddingBottom
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A SeekBar without a thumb drawable used to crash here.
        val thumbLeft = thumb?.bounds?.left ?: 0
        canvas.drawText(
            text ?: progress.toString(),
            (thumbLeft + paddingLeft).toFloat(),
            (textRect.height() + (paddingTop shr 2)).toFloat(),
            textPaint
        )
    }
}
