package desu.mintgram.helpers

import android.content.Context
import android.os.Build
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities

/** Device profile and responsive measurements for Samsung Galaxy Z Flip devices. */
object FlipDeviceHelper {
    private const val SAMSUNG = "samsung"
    private const val FLIP_MODEL_PREFIX = "SM-F7"
    private const val FLIP8_MODEL_PREFIX = "SM-F776"

    val isGalaxyZFlip8: Boolean
        get() {
            val avdName = emulatorAvdName().uppercase()
            return (isSamsung() && Build.MODEL.orEmpty().uppercase().startsWith(FLIP8_MODEL_PREFIX)) ||
                avdName.contains("FLIP8")
        }

    val isGalaxyZFlip: Boolean
        get() {
            val avdName = emulatorAvdName().uppercase()
            if (avdName.contains("GALAXY_Z_FLIP") || avdName.contains("GALAXY Z FLIP")) return true
            if (!isSamsung()) return false
            val model = Build.MODEL.orEmpty().uppercase()
            val device = Build.DEVICE.orEmpty().uppercase()
            return model.startsWith(FLIP_MODEL_PREFIX) ||
                model.contains("Z FLIP") || device.contains("ZFLIP")
        }

    /** Called immediately after config loading, before LaunchActivity chooses its root layout. */
    fun applyProfileForCurrentDevice() {
        val fingerprint = "2|" + listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
            .joinToString("|") { it.orEmpty().trim().lowercase() }
        if (InuConfig.DEVICE_PROFILE_FINGERPRINT.value == fingerprint) return

        val flip = isGalaxyZFlip
        InuConfig.FLIP_DEVICE_MODE.value = flip
        if (flip) {
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

    private fun isSamsung(): Boolean =
        Build.MANUFACTURER.orEmpty().equals(SAMSUNG, ignoreCase = true) ||
            Build.BRAND.orEmpty().equals(SAMSUNG, ignoreCase = true)

    private fun emulatorAvdName(): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.boot.qemu.avd_name", "") as? String ?: ""
    } catch (_: Throwable) {
        ""
    }
}
