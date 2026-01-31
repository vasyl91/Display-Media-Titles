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
import androidx.core.graphics.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Constants for color defaults
const val DEFAULT_BG_COLOR = 0xFF1E1E1EL
const val DEFAULT_TEXT_COLOR = 0xFFFFFFFFL
private const val CONTRAST = 4.5

/**
 * Helper function to parse color from hex string
 * Handles both with and without "0x" prefix
 */
fun parseColor(hexString: String, defaultColor: Long): Color {
    return try {
        // Remove any potential 0x prefix and parse
        val cleanHex = hexString.removePrefix("0x").removePrefix("0X")
        Color(cleanHex.toLong(16))
    } catch (e: Exception) {
        Log.w("MusicWidget", "Failed to parse color: $hexString", e)
        Color(defaultColor)
    }
}

/**
 * Helper function to format color for storage
 * Always returns 8-character hex string with padding
 */
fun formatColorForStorage(color: Long): String {
    return color.toString(16).padStart(8, '0')
}

/**
 * Calculate appropriate sample size for bitmap decoding
 * This reduces memory usage by loading smaller versions of large images
 */
fun calculateInSampleSize(
    options: BitmapFactory.Options, 
    reqWidth: Int, 
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (halfHeight / inSampleSize >= reqHeight && 
               halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Load album cover with memory optimization
 * @param path Path to the album cover file
 * @param targetSize Target size in pixels (both width and height)
 * @return Bitmap or null if loading fails
 */
fun loadAlbumCoverOptimized(path: String, targetSize: Int = 80): Bitmap? {
    return try {
        // First decode with inJustDecodeBounds to get dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        
        // Calculate inSampleSize for efficient memory usage
        options.inJustDecodeBounds = false
        options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
        
        // Now decode with the calculated sample size
        val bitmap = BitmapFactory.decodeFile(path, options)
        
        // Check if the bitmap is light and darken if necessary
        if (bitmap != null && isLight(bitmap) && isCentralAreaLight(bitmap)) {
            dimBitmap(bitmap)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e("MusicWidget", "Failed to load album cover from $path", e)
        null
    }
}

fun dimBitmap(bitmap: Bitmap): Bitmap {
    val dimmedBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(dimmedBitmap)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(0.7f, 0.7f, 0.7f, 1f) // Reduce RGB by 30%
        })
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return dimmedBitmap
}

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

private fun averageCentralColor(bitmap: Bitmap): Int {
    val width = bitmap.width
    val height = bitmap.height

    // ---- internal constants (dp) ----
    val widgetDp = 56f
    val iconDp = 24f
    val paddingDp = 10f

    val pxPerDp = width / widgetDp

    val iconSizePx = (iconDp * pxPerDp).toInt()
    val paddingPx = (paddingDp * pxPerDp).toInt()

    val iconLeft = (width - iconSizePx) / 2
    val iconTop = (height - iconSizePx) / 2

    val iconRect = Rect(
        iconLeft,
        iconTop,
        iconLeft + iconSizePx,
        iconTop + iconSizePx
    )

    // Pause bars geometry (24x24 viewport)
    val barLeftLeft = iconRect.left + iconSizePx * 6 / 24
    val barLeftRight = iconRect.left + iconSizePx * 10 / 24
    val barRightLeft = iconRect.left + iconSizePx * 14 / 24
    val barRightRight = iconRect.left + iconSizePx * 18 / 24

    val left = (iconRect.left - paddingPx).coerceAtLeast(0)
    val right = (iconRect.right + paddingPx).coerceAtMost(width)
    val top = (iconRect.top - paddingPx).coerceAtLeast(0)
    val bottom = (iconRect.bottom + paddingPx).coerceAtMost(height)

    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0

    for (y in top until bottom) {
        for (x in left until right) {

            val insideIcon =
                x in iconRect.left until iconRect.right &&
                y in iconRect.top until iconRect.bottom

            val insideLeftBar = x in barLeftLeft until barLeftRight
            val insideRightBar = x in barRightLeft until barRightRight

            val visibleInsideIcon = insideIcon &&
                    !insideLeftBar &&
                    !insideRightBar

            val visibleOutsideIcon = !insideIcon

            if (!visibleInsideIcon && !visibleOutsideIcon) continue

            val pixel = bitmap[x, y]
            rSum += android.graphics.Color.red(pixel)
            gSum += android.graphics.Color.green(pixel)
            bSum += android.graphics.Color.blue(pixel)
            count++
        }
    }

    if (count == 0) return android.graphics.Color.BLACK

    return android.graphics.Color.rgb(
        (rSum / count).toInt(),
        (gSum / count).toInt(),
        (bSum / count).toInt()
    )
}

private fun colorDistance(c1: Int, c2: Int): Double {
    val dr = android.graphics.Color.red(c1) - android.graphics.Color.red(c2)
    val dg = android.graphics.Color.green(c1) - android.graphics.Color.green(c2)
    val db = android.graphics.Color.blue(c1) - android.graphics.Color.blue(c2)

    // Weighted for perception
    return 0.2126 * abs(dr) +
           0.7152 * abs(dg) +
           0.0722 * abs(db)
}

fun ensureVeryVisibleColor(
    foreground: Int,
    background1: Int,
    background2: Int
): Int {
    // Check if colors are too similar perceptually (grayish on grayish problem)
    val minPerceptualDistance = 30.0 // Adjust this threshold as needed
    val distanceToG1 = colorDistance(foreground, background1)
    val distanceToG2 = colorDistance(foreground, background2)
    
    val tooSimilar = distanceToG1 < minPerceptualDistance || 
                     distanceToG2 < minPerceptualDistance
    
    if (
        !tooSimilar &&
        contrastRatio(foreground, background1) >= CONTRAST &&
        contrastRatio(foreground, background2) >= CONTRAST
    ) {
        return foreground
    }

    return adjustLuminanceForContrast(
        color = foreground,
        background1 = background1,
        background2 = background2
    )
}

private fun adjustLuminanceForContrast(
    color: Int,
    background1: Int,
    background2: Int
): Int {
    val targetContrast = CONTRAST
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)

    val originalV = hsv[2]
    val originalS = hsv[1]
    val step = 0.02f

    // Try increasing saturation first to escape grayish zone
    for (i in 1..10) {
        hsv[1] = (originalS + i * 0.1f).coerceAtMost(1f)
        
        // Try brightening with increased saturation
        hsv[2] = (originalV + i * step).coerceAtMost(1f)
        val bright = android.graphics.Color.HSVToColor(hsv)
        if (meetsContrast(bright, background1, background2, targetContrast)) {
            return bright
        }

        // Try darkening with increased saturation
        hsv[2] = (originalV - i * step).coerceAtLeast(0f)
        val dark = android.graphics.Color.HSVToColor(hsv)
        if (meetsContrast(dark, background1, background2, targetContrast)) {
            return dark
        }
        
        // Reset V for next saturation attempt
        hsv[2] = originalV
    }
    
    // Reset saturation and try pure luminance adjustments
    hsv[1] = originalS
    for (i in 1..25) {
        // Brighten
        hsv[2] = (originalV + i * step).coerceAtMost(1f)
        val bright = android.graphics.Color.HSVToColor(hsv)
        if (meetsContrast(bright, background1, background2, targetContrast)) {
            return bright
        }

        // Darken
        hsv[2] = (originalV - i * step).coerceAtLeast(0f)
        val dark = android.graphics.Color.HSVToColor(hsv)
        if (meetsContrast(dark, background1, background2, targetContrast)) {
            return dark
        }
    }

    // Absolute fallback: choose pure black or white
    return if (
        min(
            contrastRatio(0xFFFFFFFF.toInt(), background1),
            contrastRatio(0xFFFFFFFF.toInt(), background2)
        ) >
        min(
            contrastRatio(0xFF000000.toInt(), background1),
            contrastRatio(0xFF000000.toInt(), background2)
        )
    ) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}

private fun meetsContrast(
    color: Int,
    bg1: Int,
    bg2: Int,
    target: Double
): Boolean {
    return contrastRatio(color, bg1) >= target &&
           contrastRatio(color, bg2) >= target
}


private fun contrastRatio(c1: Int, c2: Int): Double {
    val l1 = relativeLuminance(c1)
    val l2 = relativeLuminance(c2)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Int): Double {
    val r = linearize(((color shr 16) and 0xFF) / 255.0)
    val g = linearize(((color shr 8) and 0xFF) / 255.0)
    val b = linearize((color and 0xFF) / 255.0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearize(c: Double): Double {
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

fun isLight(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height

    val startX = width / 4
    val endX = startX + width / 4
    val startY = height / 4
    val endY = startY + height / 4

    var luminanceSum = 0.0
    var pixelCount = 0

    for (y in startY until endY) {
        for (x in startX until endX) {
            val pixel = bitmap[x, y]

            val r = android.graphics.Color.red(pixel) / 255.0
            val g = android.graphics.Color.green(pixel) / 255.0
            val b = android.graphics.Color.blue(pixel) / 255.0

            // Perceived luminance (sRGB)
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b

            luminanceSum += luminance
            pixelCount++
        }
    }

    val averageLuminance = luminanceSum / pixelCount

    return averageLuminance > 0.4
}

fun isGrayish(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val startX = width / 4
    val endX = startX + width / 4
    val startY = height / 4
    val endY = startY + height / 4
    var saturationSum = 0.0
    var pixelCount = 0
    
    for (y in startY until endY) {
        for (x in startX until endX) {
            val pixel = bitmap[x, y]
            val r = android.graphics.Color.red(pixel) / 255.0
            val g = android.graphics.Color.green(pixel) / 255.0
            val b = android.graphics.Color.blue(pixel) / 255.0
            
            // Calculate saturation
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max == 0.0) 0.0 else (max - min) / max
            
            saturationSum += saturation
            pixelCount++
        }
    }
    
    val averageSaturation = saturationSum / pixelCount
    return averageSaturation < 0.2  // Low saturation means grayish
}

fun isCentralAreaLight(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height

    val startX = width / 3
    val endX = startX + width / 3
    val startY = height / 4
    val endY = startY + height / 4

    var luminanceSum = 0.0
    var pixelCount = 0

    for (y in startY until endY) {
        for (x in startX until endX) {
            val pixel = bitmap[x, y]

            val r = android.graphics.Color.red(pixel) / 255.0
            val g = android.graphics.Color.green(pixel) / 255.0
            val b = android.graphics.Color.blue(pixel) / 255.0

            // Perceived luminance (sRGB)
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b

            luminanceSum += luminance
            pixelCount++
        }
    }

    val averageLuminance = luminanceSum / pixelCount

    return averageLuminance > 0.4
}