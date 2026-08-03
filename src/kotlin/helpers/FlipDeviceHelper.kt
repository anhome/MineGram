package desu.mintgram.helpers

import android.content.Context
import android.os.Build
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities

/** Device profile and responsive measurements for Android flip and foldable phones. */
object FlipDeviceHelper {
    enum class FoldableBrand(val title: String) {
        SAMSUNG("Samsung"), MOTOROLA("Motorola"), XIAOMI("Xiaomi"), OPPO("OPPO"),
        HONOR("HONOR"), HUAWEI("Huawei"), TECNO("TECNO"), INFINIX("Infinix"),
        NUBIA("nubia"), OTHER("Android foldable")
    }

    private val foldablePattern = Regex(
        "(?i)(z ?flip|galaxy ?z|razr|mix ?flip|mix ?fold|find ?n|find ?flip|magic ?v|mate ?x|pocket|phantom ?v|zero ?flip|nubia ?flip|\\bflip\\b|\\bfold\\b|sm-f7)"
    )

    val isGalaxyZFlip8: Boolean
        get() = isSamsung() && Build.MODEL.orEmpty().uppercase().startsWith("SM-F776") ||
            emulatorAvdName().uppercase().contains("FLIP8")

    val isGalaxyZFlip: Boolean
        get() = isSamsung() && (Build.MODEL.orEmpty().uppercase().startsWith("SM-F7") ||
            Build.MODEL.orEmpty().uppercase().contains("Z FLIP") ||
            Build.DEVICE.orEmpty().uppercase().contains("ZFLIP")) ||
            emulatorAvdName().uppercase().contains("GALAXY_Z_FLIP")

    /** True for supported clamshells and book-style foldables. */
    val isSupportedFoldable: Boolean
        get() = foldablePattern.containsMatchIn(deviceSignature()) ||
            foldablePattern.containsMatchIn(emulatorAvdName())

    val foldableBrand: FoldableBrand
        get() {
            val value = deviceSignature()
            return when {
                value.contains("samsung") || value.contains("sm-f7") || value.contains("galaxy z") -> FoldableBrand.SAMSUNG
                value.contains("motorola") || value.contains("razr") -> FoldableBrand.MOTOROLA
                value.contains("xiaomi") || value.contains("mix flip") || value.contains("mix fold") -> FoldableBrand.XIAOMI
                value.contains("oppo") || value.contains("find n") -> FoldableBrand.OPPO
                value.contains("honor") || value.contains("magic v") -> FoldableBrand.HONOR
                value.contains("huawei") || value.contains("mate x") || value.contains("pocket") -> FoldableBrand.HUAWEI
                value.contains("tecno") || value.contains("phantom v") -> FoldableBrand.TECNO
                value.contains("infinix") || value.contains("zero flip") -> FoldableBrand.INFINIX
                value.contains("nubia") -> FoldableBrand.NUBIA
                else -> FoldableBrand.OTHER
            }
        }

    /** Called immediately after config loading, before LaunchActivity chooses its root layout. */
    fun applyProfileForCurrentDevice(context: Context? = null) {
        val fingerprint = "3|" + listOf(Build.BRAND, Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.PRODUCT)
            .joinToString("|") { it.orEmpty().trim().lowercase() }
        if (InuConfig.DEVICE_PROFILE_FINGERPRINT.value == fingerprint) return

        val foldable = isSupportedFoldable || context?.packageManager?.hasSystemFeature("android.hardware.sensor.hinge") == true
        InuConfig.FLIP_DEVICE_MODE.value = foldable
        if (foldable) {
            InuConfig.NAVIGATION_DRAWER.value = true
            InuConfig.DRAWER_ROW_HEIGHT.value = 44f
            InuConfig.DRAWER_AVATAR_SIZE.value = 56f
            InuConfig.DRAWER_PANEL_CORNER_RADIUS.value = 18f
            InuConfig.FLIP_FLOATING_KEYBOARD.value = true
        }
        InuConfig.DEVICE_PROFILE_FINGERPRINT.value = fingerprint
    }

    fun isCompactWindow(context: Context): Boolean {
        if (!InuConfig.FLIP_DEVICE_MODE.value) return false
        val configuration = context.resources.configuration
        return configuration.screenHeightDp in 1..599 || configuration.screenWidthDp in 1..319
    }

    fun drawerWidthPx(context: Context, availableWidthPx: Int, availableHeightPx: Int): Int {
        val shortSide = minOf(availableWidthPx, availableHeightPx).takeIf { it > 0 }
            ?: minOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y)
        val compact = isCompactWindow(context)
        val maxWidthDp = when {
            !InuConfig.FLIP_DEVICE_MODE.value -> 320f
            compact -> 260f
            else -> 288f
        }
        val edgeGapDp = when {
            !InuConfig.FLIP_DEVICE_MODE.value -> 56f
            compact -> 40f
            else -> 48f
        }
        return minOf(AndroidUtilities.dp(maxWidthDp), shortSide - AndroidUtilities.dp(edgeGapDp))
            .coerceAtLeast(AndroidUtilities.dp(200f))
    }

    fun drawerRowHeightDp(context: Context): Float =
        if (isCompactWindow(context)) 40f else InuConfig.DRAWER_ROW_HEIGHT.value

    fun profileName(): String = foldableBrand.title

    private fun isSamsung(): Boolean = Build.MANUFACTURER.orEmpty().equals("samsung", true) ||
        Build.BRAND.orEmpty().equals("samsung", true)

    private fun deviceSignature(): String = listOf(
        Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE, Build.PRODUCT,
        Build.HARDWARE, Build.FINGERPRINT
    ).joinToString(" ") { it.orEmpty().lowercase() }

    private fun emulatorAvdName(): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.boot.qemu.avd_name", "") as? String ?: ""
    } catch (_: Throwable) { "" }
}
