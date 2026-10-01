package vasyl.titles.colorpicker

import androidx.annotation.IntRange
import java.util.Locale

internal object ColorFormatHelper {
    @JvmStatic
    fun assertColorValueInRange(@IntRange(from = 0, to = 255) colorValue: Int): Int {
        return if (colorValue in 0..255) colorValue else 0
    }

    @JvmStatic
    fun formatColorValues(
        @IntRange(from = 0, to = 255) red: Int,
        @IntRange(from = 0, to = 255) green: Int,
        @IntRange(from = 0, to = 255) blue: Int
    ): String {
        return String.format(
            Locale.ROOT,
            "%02X%02X%02X",
            assertColorValueInRange(red),
            assertColorValueInRange(green),
            assertColorValueInRange(blue)
        )
    }

    @JvmStatic
    fun formatColorValues(
        @IntRange(from = 0, to = 255) alpha: Int,
        @IntRange(from = 0, to = 255) red: Int,
        @IntRange(from = 0, to = 255) green: Int,
        @IntRange(from = 0, to = 255) blue: Int
    ): String {
        return String.format(
            Locale.ROOT,
            "%02X%02X%02X%02X",
            assertColorValueInRange(alpha),
            assertColorValueInRange(red),
            assertColorValueInRange(green),
            assertColorValueInRange(blue)
        )
    }
}
