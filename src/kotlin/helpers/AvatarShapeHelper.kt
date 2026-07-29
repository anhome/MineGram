package desu.mintgram.helpers

import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import kotlin.math.ceil

enum class AvatarCornerType { DEFAULT, FORUM, COMMUNITY }

/**
 * Global avatar corner radius, driven by [InuConfig.AVATAR_CORNERS] (0..28, 28 = stock full
 * circle, 0 = square). Port of exteraGram's `ExteraConfig.getAvatarCorners` — every stock call
 * site that used to hardcode `radius = size/2` calls this instead, with `size` being the same
 * full avatar diameter (in dp unless [alreadyDp]) that stock originally halved.
 */
object AvatarShapeHelper {
    @JvmStatic
    @JvmOverloads
    fun getAvatarCorners(
        size: Float,
        alreadyDp: Boolean = false,
        type: AvatarCornerType = AvatarCornerType.DEFAULT,
        small: Boolean = false,
    ): Int {
        val corners = InuConfig.AVATAR_CORNERS.value
        if (corners == 0f) return 0
        var r = (corners * size) / 56f
        if (small) r -= if (alreadyDp) AndroidUtilities.dpf2(2.5f) else 2.5f
        if (!alreadyDp) r = AndroidUtilities.dp(r).toFloat()
        if (!InuConfig.AVATAR_SINGLE_CORNER_RADIUS.value && type != AvatarCornerType.DEFAULT) {
            r = when (type) {
                AvatarCornerType.FORUM -> ((r.toInt() * 42) shr 6).toFloat()
                AvatarCornerType.COMMUNITY -> (r * 40f) / 72f
                else -> r
            }
        }
        return ceil(r).toInt()
    }

    /** Convenience matching exteraGram's `getAvatarCorners(size, alreadyDp, isForum)` shorthand. */
    @JvmStatic
    fun getAvatarCorners(size: Float, alreadyDp: Boolean, isForum: Boolean): Int =
        getAvatarCorners(size, alreadyDp, if (isForum) AvatarCornerType.FORUM else AvatarCornerType.DEFAULT, false)

    @JvmStatic
    fun getAvatarSquareness(): Float = (1f - InuConfig.AVATAR_CORNERS.value / 28f).coerceIn(0f, 1f)
}
