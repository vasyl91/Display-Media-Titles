package vasyl.titles.device

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import vasyl.titles.device.FytPlatform.Chipset

/**
 * What the device is, by its chipset (rules in [FytPlatform]), found once per process.
 *
 * Rule: the stock sources of the FYT 7870 (com.syu.media, BT and FM radio through com.syu.ms) are
 * used only on a unit that is a 7870 by its chipset; every other device, the 7862 included, keeps
 * the com.syu.music path it had. This is the owner's choice for this app (the FYT Launcher Mod
 * decides its music sources by package).
 *
 * System properties are read through android.os.SystemProperties by reflection (the app has no
 * hidden API stubs). It reads the property area of the process, no disk and no binder, so it is
 * cheap enough for the main thread. If the class cannot be used, the names in Build are still
 * looked at, which covers the 7870 (its Build.MODEL contains "7870").
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"

    @Volatile
    private var cached: Chipset? = null

    fun chipset(): Chipset = cached ?: detect().also { cached = it }

    fun is7870(): Boolean = chipset() == Chipset.FYT_7870

    private fun detect(): Chipset {
        val fytPlatform = property("ro.fyt.platform")
        val fyt = FytPlatform.isFyt(
            property("ro.product.board"),
            property("ro.product.manufacturer"),
            fytPlatform,
            Build.DISPLAY,
            property("ro.build.fytmanufacturer"),
        )
        val chipset = FytPlatform.classify(
            fyt, fytPlatform,
            Build.MODEL, Build.BOARD, Build.PRODUCT, Build.DEVICE, Build.HARDWARE,
            property("ro.board.platform"),
        )
        // What the result was found from, so a new unit can be checked against the rules.
        Log.i(
            TAG,
            "FYT platform: $chipset (ro.fyt.platform=$fytPlatform model=${Build.MODEL} board=${Build.BOARD} " +
                "product=${Build.PRODUCT} device=${Build.DEVICE} hardware=${Build.HARDWARE} display=${Build.DISPLAY})"
        )
        return chipset
    }

    @SuppressLint("PrivateApi")
    private fun property(name: String): String {
        val value = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, name) as? String
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Cannot read $name: $e")
            null
        } catch (e: RuntimeException) { // SecurityException, a hidden API that is blocked
            Log.w(TAG, "Cannot read $name: $e")
            null
        }
        return value.orEmpty()
    }
}
