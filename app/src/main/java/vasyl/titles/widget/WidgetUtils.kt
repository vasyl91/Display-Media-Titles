package vasyl.titles.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import android.graphics.Color as AndroidColor

// Color defaults (ARGB)
const val DEFAULT_BG_COLOR = 0xFF1E1E1EL
const val DEFAULT_TEXT_COLOR = 0xFFFFFFFFL
private const val CONTRAST = 4.5
private const val TAG = "WidgetUtils"

// -------------------------------------------------------------------------------------------------
// Color parsing / formatting
// -------------------------------------------------------------------------------------------------

/**
 * Parses "AARRGGBB" / "RRGGBB", optionally prefixed with "#" or "0x".
 * A 6-digit value is treated as opaque (it used to become fully transparent).
 */
fun parseColor(hexString: String, defaultColor: Long): Color {
    val argb = parseArgb(hexString)
    if (argb == null) {
        Log.w(TAG, "Failed to parse color: $hexString")
        return Color(defaultColor)
    }
    return Color(argb)
}

/** @return the ARGB value or null if [value] is not a valid 6 or 8 digit hex color. */
fun parseArgb(value: String): Int? {
    val clean = value.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (clean.length != 6 && clean.length != 8) return null
    val parsed = clean.toLongOrNull(16) ?: return null
    return if (clean.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
}

/**
 * Always returns an 8-character hex string. Negative values (e.g. an ARGB Int converted with
 * toLong()) are masked instead of producing "-e1e1e2".
 */
fun formatColorForStorage(color: Long): String =
    (color and 0xFFFFFFFFL).toString(16).padStart(8, '0')

// -------------------------------------------------------------------------------------------------
// Bitmap loading
// -------------------------------------------------------------------------------------------------

/**
 * Calculate appropriate sample size for bitmap decoding.
 * This reduces memory usage by loading smaller versions of large images.
 */
fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Loads an album cover with memory optimization.
 * The cover is dimmed when it is saved (see WidgetUpdater), not on every recomposition anymore.
 *
 * @param path path of the album cover file
 * @param targetSize target size in PIXELS (callers convert dp to px)
 * @return bitmap or null if loading fails
 */
fun loadAlbumCoverOptimized(path: String, targetSize: Int = 80): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        options.inJustDecodeBounds = false
        options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load album cover from $path", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Out of memory while loading $path", e)
        null
    }
}

/** Returns a darker copy (RGB * 0.7). Transparent (rounded) corners stay transparent. */
fun dimBitmap(bitmap: Bitmap): Bitmap {
    // Drawing onto a copy of the source composited the image twice (semi-transparent pixels).
    val dimmed = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
    }
    Canvas(dimmed).drawBitmap(bitmap, 0f, 0f, paint)
    return dimmed
}

// -------------------------------------------------------------------------------------------------
// Widget color extraction (moved here from MusicWidgetBig.kt)
// -------------------------------------------------------------------------------------------------

/**
 * Picks (background, text) ARGB colors for the widget from the album cover.
 * Expects a software bitmap of moderate size (WidgetUpdater scales covers to <= 384 px).
 */
fun extractWidgetColors(bitmap: Bitmap): Pair<Long, Long> {
    return try {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()

        val gray = isGrayish(bitmap)
        val light = isLight(bitmap)
        val centralLight = isCentralAreaLight(bitmap)

        var backgroundColor = palette.dominantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: DEFAULT_BG_COLOR.toInt()

        if (light) {
            backgroundColor = palette.mutedSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: DEFAULT_BG_COLOR.toInt()
        }

        val firstColor = findFirstColor(backgroundColor)
        val secondColor = findSecondColor(palette, firstColor)

        val result = compareColors(bitmap, firstColor, secondColor)
        val bgColor = result.backgroundColor.toLong() and 0xFFFFFFFFL
        val txtColor = result.textColor.toLong() and 0xFFFFFFFFL

        if (gray && centralLight && !light) Pair(txtColor, bgColor) else Pair(bgColor, txtColor)
    } catch (e: Exception) {
        Log.e(TAG, "Error extracting colors", e)
        Pair(DEFAULT_BG_COLOR, DEFAULT_TEXT_COLOR)
    }
}

private fun findFirstColor(color: Int): Int {
    val luminance = relativeLuminance(color)
    val r = AndroidColor.red(color)
    val g = AndroidColor.green(color)
    val b = AndroidColor.blue(color)

    if (luminance < 0.1) {
        return AndroidColor.rgb(
            (r * 1.5f).toInt().coerceIn(60, 255),
            (g * 1.5f).toInt().coerceIn(60, 255),
            (b * 1.5f).toInt().coerceIn(60, 255)
        )
    }
    if (luminance > 0.7) {
        return AndroidColor.rgb(
            (r * 0.8f).toInt().coerceIn(0, 200),
            (g * 0.8f).toInt().coerceIn(0, 200),
            (b * 0.8f).toInt().coerceIn(0, 200)
        )
    }
    return color
}

private fun findSecondColor(palette: Palette, backgroundColor: Int): Int {
    val candidates = listOfNotNull(
        palette.vibrantSwatch,
        palette.lightVibrantSwatch,
        palette.darkVibrantSwatch,
        palette.mutedSwatch,
        palette.lightMutedSwatch,
        palette.darkMutedSwatch
    )

    val bestSwatch = candidates
        .filter { swatch ->
            !isSimilarColor(swatch.rgb, backgroundColor) && contrastRatio(swatch.rgb, backgroundColor) >= 3.0
        }
        .maxByOrNull { swatch -> contrastRatio(swatch.rgb, backgroundColor) * (swatch.population / 1000f) }

    if (bestSwatch != null) return bestSwatch.rgb

    return if (relativeLuminance(backgroundColor) > 0.5) {
        AndroidColor.rgb(40, 40, 40)
    } else {
        AndroidColor.rgb(245, 245, 245)
    }
}

private fun isSimilarColor(color1: Int, color2: Int): Boolean {
    val dr = AndroidColor.red(color1) - AndroidColor.red(color2)
    val dg = AndroidColor.green(color1) - AndroidColor.green(color2)
    val db = AndroidColor.blue(color1) - AndroidColor.blue(color2)
    return sqrt((dr * dr + dg * dg + db * db).toDouble()) < 80
}

// -------------------------------------------------------------------------------------------------
// Text / background pairing
// -------------------------------------------------------------------------------------------------

data class TextBackgroundColors(
    val backgroundColor: Int,
    val textColor: Int
)

fun compareColors(
    bitmap: Bitmap,
    colorA: Int,
    colorB: Int
): TextBackgroundColors {
    val centralColor = averageCentralColor(bitmap)

    val distanceToA = colorDistance(centralColor, colorA)
    val distanceToB = colorDistance(centralColor, colorB)

    return if (distanceToA <= distanceToB) {
        TextBackgroundColors(
            backgroundColor = colorA,
            textColor = ensureVeryVisibleColor(colorB, colorA, centralColor)
        )
    } else {
        TextBackgroundColors(
            backgroundColor = colorB,
            textColor = ensureVeryVisibleColor(colorA, colorB, centralColor)
        )
    }
}

/**
 * Average color of the area around the play/pause icon of the small widget (56dp cover, 24dp icon
 * viewport with 10dp padding), without the pause bars themselves.
 */
private fun averageCentralColor(bitmap: Bitmap): Int {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return AndroidColor.BLACK

    val widgetDp = 56f
    val iconDp = 24f
    val paddingDp = 10f
    val pxPerDp = width / widgetDp

    val iconSizePx = (iconDp * pxPerDp).toInt()
    val paddingPx = (paddingDp * pxPerDp).toInt()
    val iconLeft = (width - iconSizePx) / 2
    val iconTop = (height - iconSizePx) / 2
    val iconRect = Rect(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx)

    // Pause bars geometry (24x24 viewport)
    val barLeftLeft = iconRect.left + iconSizePx * 6 / 24
    val barLeftRight = iconRect.left + iconSizePx * 10 / 24
    val barRightLeft = iconRect.left + iconSizePx * 14 / 24
    val barRightRight = iconRect.left + iconSizePx * 18 / 24

    val left = (iconRect.left - paddingPx).coerceIn(0, width)
    val right = (iconRect.right + paddingPx).coerceIn(left, width)
    val top = (iconRect.top - paddingPx).coerceIn(0, height)
    val bottom = (iconRect.bottom + paddingPx).coerceIn(top, height)
    val regionWidth = right - left
    if (regionWidth <= 0 || bottom <= top) return AndroidColor.BLACK

    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    val row = IntArray(regionWidth)

    for (y in top until bottom) {
        // One JNI call per row instead of one per pixel.
        bitmap.getPixels(row, 0, regionWidth, left, y, regionWidth, 1)
        val insideIconRow = y >= iconRect.top && y < iconRect.bottom
        for (i in 0 until regionWidth) {
            val x = left + i
            val insideIcon = insideIconRow && x >= iconRect.left && x < iconRect.right
            val onBar = (x >= barLeftLeft && x < barLeftRight) || (x >= barRightLeft && x < barRightRight)
            if (insideIcon && onBar) continue

            val pixel = row[i]
            rSum += AndroidColor.red(pixel)
            gSum += AndroidColor.green(pixel)
            bSum += AndroidColor.blue(pixel)
            count++
        }
    }

    if (count == 0) return AndroidColor.BLACK
    return AndroidColor.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
}

private fun colorDistance(c1: Int, c2: Int): Double {
    val dr = AndroidColor.red(c1) - AndroidColor.red(c2)
    val dg = AndroidColor.green(c1) - AndroidColor.green(c2)
    val db = AndroidColor.blue(c1) - AndroidColor.blue(c2)

    // Weighted for perception
    return 0.2126 * abs(dr) + 0.7152 * abs(dg) + 0.0722 * abs(db)
}

fun ensureVeryVisibleColor(
    foreground: Int,
    background1: Int,
    background2: Int
): Int {
    // Colors that are too similar perceptually (grayish on grayish) are adjusted as well.
    val minPerceptualDistance = 30.0
    val tooSimilar = colorDistance(foreground, background1) < minPerceptualDistance ||
        colorDistance(foreground, background2) < minPerceptualDistance

    if (!tooSimilar &&
        contrastRatio(foreground, background1) >= CONTRAST &&
        contrastRatio(foreground, background2) >= CONTRAST
    ) {
        return foreground
    }

    return adjustLuminanceForContrast(foreground, background1, background2)
}

private fun adjustLuminanceForContrast(
    color: Int,
    background1: Int,
    background2: Int
): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)

    val originalV = hsv[2]
    val originalS = hsv[1]
    val step = 0.02f

    // Try increasing saturation first to escape the grayish zone.
    for (i in 1..10) {
        hsv[1] = (originalS + i * 0.1f).coerceAtMost(1f)

        hsv[2] = (originalV + i * step).coerceAtMost(1f)
        val bright = AndroidColor.HSVToColor(hsv)
        if (meetsContrast(bright, background1, background2, CONTRAST)) return bright

        hsv[2] = (originalV - i * step).coerceAtLeast(0f)
        val dark = AndroidColor.HSVToColor(hsv)
        if (meetsContrast(dark, background1, background2, CONTRAST)) return dark

        hsv[2] = originalV
    }

    // Reset saturation and try pure luminance adjustments.
    hsv[1] = originalS
    for (i in 1..25) {
        hsv[2] = (originalV + i * step).coerceAtMost(1f)
        val bright = AndroidColor.HSVToColor(hsv)
        if (meetsContrast(bright, background1, background2, CONTRAST)) return bright

        hsv[2] = (originalV - i * step).coerceAtLeast(0f)
        val dark = AndroidColor.HSVToColor(hsv)
        if (meetsContrast(dark, background1, background2, CONTRAST)) return dark
    }

    // Absolute fallback: pure black or white.
    val white = 0xFFFFFFFF.toInt()
    val black = 0xFF000000.toInt()
    return if (
        min(contrastRatio(white, background1), contrastRatio(white, background2)) >
        min(contrastRatio(black, background1), contrastRatio(black, background2))
    ) white else black
}

private fun meetsContrast(color: Int, bg1: Int, bg2: Int, target: Double): Boolean =
    contrastRatio(color, bg1) >= target && contrastRatio(color, bg2) >= target

private fun contrastRatio(c1: Int, c2: Int): Double {
    val l1 = relativeLuminance(c1)
    val l2 = relativeLuminance(c2)
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
}

private fun relativeLuminance(color: Int): Double {
    val r = linearize(((color shr 16) and 0xFF) / 255.0)
    val g = linearize(((color shr 8) and 0xFF) / 255.0)
    val b = linearize((color and 0xFF) / 255.0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearize(c: Double): Double =
    if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

// -------------------------------------------------------------------------------------------------
// Brightness analysis
// -------------------------------------------------------------------------------------------------

/**
 * Calls [action] for every pixel of the given region, reading one row at a time.
 * @return number of visited pixels
 */
private inline fun Bitmap.forEachPixel(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    action: (pixel: Int) -> Unit
): Int {
    val l = left.coerceIn(0, width)
    val r = right.coerceIn(l, width)
    val t = top.coerceIn(0, height)
    val b = bottom.coerceIn(t, height)
    val w = r - l
    if (w <= 0 || b <= t) return 0
    val row = IntArray(w)
    for (y in t until b) {
        getPixels(row, 0, w, l, y, w, 1)
        for (pixel in row) action(pixel)
    }
    return w * (b - t)
}

private fun perceivedLuminance(pixel: Int): Double =
    0.2126 * (AndroidColor.red(pixel) / 255.0) +
        0.7152 * (AndroidColor.green(pixel) / 255.0) +
        0.0722 * (AndroidColor.blue(pixel) / 255.0)

/** Average perceived luminance of the central half of the image is above 0.4. */
fun isLight(bitmap: Bitmap): Boolean {
    // The region used to be [w/4, w/2) x [h/4, h/2), i.e. only the upper-left part of the center.
    var sum = 0.0
    val count = bitmap.forEachPixel(
        bitmap.width / 4, bitmap.height / 4, bitmap.width * 3 / 4, bitmap.height * 3 / 4
    ) { sum += perceivedLuminance(it) }
    return count > 0 && sum / count > 0.4
}

/** Average saturation of the central half of the image is below 0.2. */
fun isGrayish(bitmap: Bitmap): Boolean {
    var sum = 0.0
    val count = bitmap.forEachPixel(
        bitmap.width / 4, bitmap.height / 4, bitmap.width * 3 / 4, bitmap.height * 3 / 4
    ) { pixel ->
        val r = AndroidColor.red(pixel) / 255.0
        val g = AndroidColor.green(pixel) / 255.0
        val b = AndroidColor.blue(pixel) / 255.0
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        sum += if (maxC == 0.0) 0.0 else (maxC - minC) / maxC
    }
    return count > 0 && sum / count < 0.2
}

/** Average perceived luminance of the central third (where the play/pause icon sits) is above 0.4. */
fun isCentralAreaLight(bitmap: Bitmap): Boolean {
    var sum = 0.0
    val count = bitmap.forEachPixel(
        bitmap.width / 3, bitmap.height / 3, bitmap.width * 2 / 3, bitmap.height * 2 / 3
    ) { sum += perceivedLuminance(it) }
    return count > 0 && sum / count > 0.4
}
