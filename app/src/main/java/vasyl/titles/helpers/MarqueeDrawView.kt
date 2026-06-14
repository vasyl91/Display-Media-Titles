package vasyl.titles.helpers

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.io.File

class MarqueeDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.BLACK
    }
    private var isOutlined = false
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tf: Typeface? = null
    private var text: String = ""
    private var offsetX = 0f
    private var animator: ValueAnimator? = null

    // scrolling control
    private var scrollEnabled = true
    private var scrollDurationMs: Long = 6000L     // fallback fixed duration if speed not set
    private var scrollSpeedPxPerSec: Float? = 40f  // default speed in px/sec (overrides duration if not null)
    private var spacing = 150f                      // px between repeated texts

    private var bgColor: Int = Color.TRANSPARENT
    private var bgCornerRadius: Float = 0f

    init {
        paint.color = Color.WHITE
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            resources.displayMetrics
        )
        bgPaint.style = Paint.Style.FILL
        bgPaint.color = Color.TRANSPARENT
        // ensure view itself has no background drawable that interferes
        background = null
        strokePaint.textSize = paint.textSize
    }
    
    fun setText(value: String?) {
        text = value ?: ""
        requestLayout()
        invalidate()
        restartMarqueeIfNeeded()
    }

    fun setTextColor(color: Int) {
        paint.color = color
        invalidate()
    }
    fun setTextSizeSp(sizeSp: Float) {
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            resources.displayMetrics
        )
        strokePaint.textSize = paint.textSize
        requestLayout()
        invalidate()
        restartMarqueeIfNeeded()
    }

    /**
     * Sets the "modeled" typeface:
     * 0 => normal, 1 => bold, 2 => italic
     * This will be ignored if a custom TTF is loaded via setTypefaceFile(...)
     */
    fun setTypefaceMode(mode: Int) {
        // only apply built-in styles if no custom TTF is loaded
        if (tf != null) return
        isOutlined = (mode == 4)
        val style = when (mode) {
            1 -> Typeface.BOLD
            2 -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val type = Typeface.create(Typeface.DEFAULT, style)
        paint.typeface = type
        strokePaint.typeface = type
        invalidate()
    }

    /**
     * Load a custom TTF file. Pass null to clear the custom TTF and revert to style-based typeface.
     */
    fun setTypefaceFile(file: File?) {
        tf = try {
            file?.takeIf { it.exists() }?.let { Typeface.createFromFile(it) }
        } catch (e: Exception) {
            null
        }
        val type = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.typeface = type
        strokePaint.typeface = type
        requestLayout()
        invalidate()
        restartMarqueeIfNeeded()
    }

    /**
     * Set background color. If `color` equals Color.TRANSPARENT we leave it transparent,
     * otherwise we ensure the paint uses full opacity (alpha = 0xFF) unless caller provided alpha.
     *
     * cornerRadiusPx is optional and defaults to 0f.
     */
    fun setBgColorInt(color: Int, cornerRadiusPx: Float = 0f) {
        // preserve explicit alpha if user provided one; otherwise force full opacity for non-transparent colors
        val finalColor = if (color == Color.TRANSPARENT) {
            Color.TRANSPARENT
        } else {
            val alpha = (color ushr 24) and 0xFF
            if (alpha == 0) (0xFF000000.toInt() or (color and 0x00FFFFFF)) else color
        }

        bgColor = finalColor
        // ensure bgPaint uses the final color and fully opaque alpha when not transparent
        bgPaint.color = finalColor
        bgPaint.alpha = if (finalColor == Color.TRANSPARENT) 0 else 255
        bgCornerRadius = cornerRadiusPx

        // make sure view alpha is 1
        this.alpha = 1f

        invalidate()
    }
    fun enableScroll(enable: Boolean) {
        scrollEnabled = enable
        if (!enable) stopMarquee() else restartMarqueeIfNeeded()
    }
    fun stopMarquee() {
        animator?.cancel()
        animator = null
        offsetX = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val fm = paint.fontMetricsInt
        val h = (fm.bottom - fm.top) + paddingTop + paddingBottom
        setMeasuredDimension(measuredWidth, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background: fill entire canvas for solid appearance, or clear for transparent
        if (bgColor != Color.TRANSPARENT) {
            // fill entire canvas with bgColor (ensures no semi-transparency due to rounded rect antialias)
            canvas.drawColor(bgColor)
        } else {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (text.isEmpty()) return

        val textWidth = paint.measureText(text)
        val startX = paddingLeft.toFloat() - offsetX
        val centerY = (height / 2f) - ((paint.descent() + paint.ascent()) / 2f)

        if (textWidth <= (width - paddingLeft - paddingRight) || !scrollEnabled) {
            // not scrolling, draw at start
            if (isOutlined) canvas.drawText(text, paddingLeft.toFloat(), centerY, strokePaint)
            canvas.drawText(text, paddingLeft.toFloat(), centerY, paint)
        } else {
            // scrolling - draw text repeatedly for continuous marquee
            var x = startX
            while (x < width.toFloat()) {
                if (isOutlined) canvas.drawText(text, x, centerY, strokePaint)
                canvas.drawText(text, x, centerY, paint)
                x += textWidth + spacing
            }
        }
    }

    private fun restartMarqueeIfNeeded() {
        stopMarquee()

        if (!scrollEnabled) return
        if (text.isEmpty()) return

        val textWidth = paint.measureText(text)
        val availableSpace = width - paddingLeft - paddingRight
        if (textWidth <= availableSpace) return

        // total distance to animate (one full text width + spacing)
        val totalDistance = textWidth + spacing

        // compute animation duration:
        val duration = scrollSpeedPxPerSec?.let { speedPxPerSec ->
            // compute duration such that speed = pixels/sec
            val ms = ((totalDistance / speedPxPerSec) * 1000f).toLong()
            // clamp sensible min/max to avoid extreme values
            ms.coerceAtLeast(2000L).coerceAtMost(120_000L)
        } ?: scrollDurationMs.coerceAtLeast(2000L)

        animator = ValueAnimator.ofFloat(0f, totalDistance).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                offsetX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartMarqueeIfNeeded()
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        super.onDetachedFromWindow()
    }
}