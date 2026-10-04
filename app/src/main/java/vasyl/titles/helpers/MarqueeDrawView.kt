package vasyl.titles.helpers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.io.File

/**
 * Single line text with a continuous marquee, used as the titles overlay.
 *
 * Scrolling is driven by display frames (postOnAnimation) instead of a ValueAnimator:
 *  - it only runs while the text is wider than the view and the window is visible; the animator was
 *    started before the first layout (width 0) and then redrew the overlay every frame forever, even
 *    when the text fitted,
 *  - it keeps working when "Animator duration scale" is off (animators end immediately then).
 */
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
    private var tf: Typeface? = null
    private var text: String = ""
    private var offsetX = 0f

    // scrolling control
    private var scrollEnabled = true
    private var scrollDurationMs: Long = 6000L     // fallback fixed duration if speed not set
    private var scrollSpeedPxPerSec: Float? = 40f  // default speed in px/sec (overrides duration if not null)
    private var spacing = 150f                      // px between repeated texts

    private var bgColor: Int = Color.TRANSPARENT
    private var bgCornerRadius: Float = 0f

    private var scrolling = false
    private var scrollStartTime = 0L
    private var scrollCycleMs = 1L
    private var scrollDistance = 0f

    // Cached metrics: onDraw runs every frame while scrolling (fontMetricsInt allocates).
    private var cachedTextWidth = -1f
    private var cachedFontHeight = -1

    private val scrollFrame = object : Runnable {
        override fun run() {
            if (!scrolling) return
            val elapsed = SystemClock.uptimeMillis() - scrollStartTime
            offsetX = (elapsed % scrollCycleMs).toFloat() / scrollCycleMs * scrollDistance
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        paint.color = Color.WHITE
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            resources.displayMetrics
        )
        strokePaint.textSize = paint.textSize
        // ensure view itself has no background drawable that interferes
        background = null
    }

    fun setText(value: String?) {
        text = value ?: ""
        onTextMetricsChanged()
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
        onTextMetricsChanged()
    }

    /**
     * Sets the "modeled" typeface:
     * 0 => normal, 1 => bold, 2 => italic
     * This will be ignored if a custom TTF is loaded via setTypefaceFile(...)
     */
    fun setTypefaceMode(mode: Int) {
        // only apply built-in styles if no custom TTF is loaded
        if (tf != null) return
        applyTypeface(Typeface.create(Typeface.DEFAULT, styleOf(mode)))
        onTextMetricsChanged()
    }

    /**
     * Load a custom TTF file. Pass null to clear the custom TTF and revert to style-based typeface.
     * The last font is cached: the overlay view is re-created on every track change, which used to
     * reload the font file from disk on the main thread each time.
     */
    fun setTypefaceFile(file: File?) {
        tf = file?.takeIf { it.isFile }?.let { loadTypeface(it) }
        applyTypeface(tf ?: Typeface.create(Typeface.DEFAULT, Typeface.NORMAL))
        onTextMetricsChanged()
    }

    /** Adds a black outline to the text; works with every typeface, including a custom TTF. */
    fun setOutlined(outlined: Boolean) {
        if (isOutlined == outlined) return
        isOutlined = outlined
        invalidate()
    }

    private fun applyTypeface(type: Typeface) {
        paint.typeface = type
        strokePaint.typeface = type
    }

    /**
     * Set background color. If `color` equals Color.TRANSPARENT we leave it transparent,
     * otherwise a color without alpha is made fully opaque.
     *
     * cornerRadiusPx is optional and defaults to 0f.
     */
    fun setBgColorInt(color: Int, cornerRadiusPx: Float = 0f) {
        bgColor = if (color == Color.TRANSPARENT) {
            Color.TRANSPARENT
        } else {
            val alpha = (color ushr 24) and 0xFF
            if (alpha == 0) (0xFF000000.toInt() or (color and 0x00FFFFFF)) else color
        }
        bgCornerRadius = cornerRadiusPx

        // make sure view alpha is 1
        this.alpha = 1f
        invalidate()
    }

    fun enableScroll(enable: Boolean) {
        scrollEnabled = enable
        if (enable) restartMarqueeIfNeeded() else stopMarquee()
    }

    fun stopMarquee() {
        scrolling = false
        removeCallbacks(scrollFrame)
        offsetX = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        // An exact height (the overlay window) is filled completely, so the background covers the
        // whole window; before, only the text line was drawn and the rest of an opaque window was
        // undefined (usually black). The text itself stays in the top box, exactly where it was.
        val measuredHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            textBoxHeight()
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) restartMarqueeIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (bgColor != Color.TRANSPARENT) {
            canvas.drawColor(bgColor)
        } else {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (text.isEmpty()) return

        val textWidth = textWidth()
        // Vertically centered in the text box at the top of the view (same position as before).
        val centerY = (textBoxHeight() / 2f) - ((paint.descent() + paint.ascent()) / 2f)

        if (!scrolling || textWidth <= (width - paddingLeft - paddingRight)) {
            // not scrolling, draw at start
            if (isOutlined) canvas.drawText(text, paddingLeft.toFloat(), centerY, strokePaint)
            canvas.drawText(text, paddingLeft.toFloat(), centerY, paint)
        } else {
            // scrolling - draw text repeatedly for continuous marquee
            var x = paddingLeft.toFloat() - offsetX
            while (x < width.toFloat()) {
                if (isOutlined) canvas.drawText(text, x, centerY, strokePaint)
                canvas.drawText(text, x, centerY, paint)
                x += textWidth + spacing
            }
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

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) restartMarqueeIfNeeded() else stopMarquee()
    }

    private fun onTextMetricsChanged() {
        cachedTextWidth = -1f
        cachedFontHeight = -1
        requestLayout()
        invalidate()
        restartMarqueeIfNeeded()
    }

    private fun textWidth(): Float {
        if (cachedTextWidth < 0f) cachedTextWidth = if (text.isEmpty()) 0f else paint.measureText(text)
        return cachedTextWidth
    }

    private fun textBoxHeight(): Int {
        if (cachedFontHeight < 0) {
            val fm = paint.fontMetricsInt
            cachedFontHeight = fm.bottom - fm.top
        }
        return cachedFontHeight + paddingTop + paddingBottom
    }

    private fun restartMarqueeIfNeeded() {
        stopMarquee()

        if (!scrollEnabled || text.isEmpty()) return
        if (!isAttachedToWindow || windowVisibility != VISIBLE) return
        val availableSpace = width - paddingLeft - paddingRight
        if (availableSpace <= 0) return // not laid out yet: onSizeChanged() calls this again

        val textWidth = textWidth()
        if (textWidth <= availableSpace) return

        // total distance of one cycle (one full text width + spacing)
        scrollDistance = textWidth + spacing
        scrollCycleMs = scrollSpeedPxPerSec?.let { speedPxPerSec ->
            // speed = pixels/sec, clamped to sensible durations
            ((scrollDistance / speedPxPerSec) * 1000f).toLong().coerceIn(2000L, 120_000L)
        } ?: scrollDurationMs.coerceAtLeast(2000L)

        scrollStartTime = SystemClock.uptimeMillis()
        scrolling = true
        postOnAnimation(scrollFrame)
    }

    companion object {
        // Last loaded custom font (main thread only).
        private var cachedFontKey: String? = null
        private var cachedFont: Typeface? = null

        /**
         * Width in px of [text] drawn with the given style, using the same rules as the view
         * (typefaceMode 0 = normal, 1 = bold, 2 = italic; a font file takes precedence).
         */
        fun measureText(context: Context, text: String, sizeSp: Float, typefaceMode: Int, ttfFile: File?): Float {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
            paint.typeface = ttfFile?.takeIf { it.isFile }?.let { loadTypeface(it) }
                ?: Typeface.create(Typeface.DEFAULT, styleOf(typefaceMode))
            return paint.measureText(text)
        }

        private fun styleOf(mode: Int): Int = when (mode) {
            1 -> Typeface.BOLD
            2 -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        private fun loadTypeface(file: File): Typeface? {
            val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
            if (key == cachedFontKey) return cachedFont
            val typeface = try {
                Typeface.createFromFile(file)
            } catch (e: RuntimeException) {
                null
            }
            cachedFontKey = key
            cachedFont = typeface
            return typeface
        }
    }
}
