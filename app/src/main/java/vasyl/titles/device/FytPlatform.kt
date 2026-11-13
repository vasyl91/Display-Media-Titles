package vasyl.titles.device

import java.util.Locale

/**
 * Which FYT platform a device is, told by its chipset. Free of android.* so the rules are unit
 * tested; [DeviceIdentity] reads the system and keeps the answer. The rules are the ones of the
 * FYT Launcher Mod (com.android.launcher66.FytPlatform), so both apps tell the units apart alike.
 *
 * The most certain identifier is the ro.fyt.platform property (6318 = 7870, 6315 = 7862,
 * 6316 = 8581, 6521 = 9853i). Without it the device names are looked at: Build.MODEL values such
 * as ums512_1h10_Natv (7862) and uis7870sc_2h10_nosec (7870).
 *
 * Rule: a chipset without an FYT mark is not an FYT platform. Teyes units and T618 tablets use
 * the UMS512 as well, with firmware that is not FYT's, and must not be taken for a 7862.
 */
object FytPlatform {

    enum class Chipset(val platformCode: String) {
        FYT_7870("6318"),
        FYT_7862("6315"),
        FYT_8581("6316"),
        FYT_9853I("6521"),

        /** An FYT unit whose chipset is not one of the above (ro.fyt.platform 6312, 823, ...). */
        UNKNOWN_FYT(""),
        NOT_FYT(""),
    }

    /**
     * Whether this is an FYT unit: the build says FYT in its board, manufacturer or display id, or
     * it has ro.fyt.platform or ro.build.fytmanufacturer (FYT's number for the customer).
     */
    fun isFyt(
        productBoard: String?,
        productManufacturer: String?,
        fytPlatform: String?,
        display: String?,
        fytManufacturer: String?,
    ): Boolean =
        containsFyt(productBoard) || containsFyt(productManufacturer) || !fytPlatform.isNullOrBlank() ||
            containsFyt(display) || !fytManufacturer.isNullOrBlank()

    /**
     * @param fyt the result of [isFyt]
     * @param fytPlatform ro.fyt.platform
     * @param ids the device names to look in when there is no code: model, board, product, ...
     */
    fun classify(fyt: Boolean, fytPlatform: String?, vararg ids: String?): Chipset {
        if (!fyt) return Chipset.NOT_FYT
        val code = fytPlatform?.trim().orEmpty()
        if (code.isNotEmpty()) {
            Chipset.entries.firstOrNull { it.platformCode.isNotEmpty() && it.platformCode == code }
                ?.let { return it }
        }
        val names = ids.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
        return when {
            "7870" in names -> Chipset.FYT_7870
            "ums512" in names || "7862" in names -> Chipset.FYT_7862
            "8581" in names || "9863" in names -> Chipset.FYT_8581
            "9853" in names -> Chipset.FYT_9853I
            else -> Chipset.UNKNOWN_FYT
        }
    }

    private fun containsFyt(value: String?): Boolean =
        value != null && value.uppercase(Locale.ROOT).contains("FYT")
}
