package desu.mintgram.helpers.icons

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.drawable.Drawable
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities

class IconsResources(private val resources: Resources) : Resources(resources.assets, resources.displayMetrics, resources.configuration) {
    override fun getText(id: Int): CharSequence {
        return resources.getText(id)
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence {
        return resources.getText(id, def)
    }

    fun syncConfigurationFrom(base: Resources) {
        if (configuration != base.configuration || displayMetrics != base.displayMetrics) {
            updateConfiguration(base.configuration, base.displayMetrics)
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int): Drawable {
        customOverride(id, resources.displayMetrics.densityDpi, null)?.let { return it }
        return super.getDrawable(getConversion(id), null)
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int, theme: Theme?): Drawable {
        customOverride(id, resources.displayMetrics.densityDpi, theme)?.let { return it }
        return super.getDrawable(getConversion(id), theme)
    }

    @Throws(NotFoundException::class)
    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? {
        customOverride(id, density, theme)?.let { return it }
        return super.getDrawableForDensity(getConversion(id), density, theme)
    }

    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawableForDensity(id: Int, density: Int): Drawable? {
        customOverride(id, density, null)?.let { return it }
        return super.getDrawableForDensity(getConversion(id), density, null)
    }

    /** Only non-null when a user-created pack is active *and* has an override saved for [id].
     * Scales the override to match the *original* stock icon's intrinsic size at [density] —
     * resolved through the wrapped [resources] (not `this`, which would recurse back into this
     * same override check) — so a replacement icon renders at the size the caller actually asked
     * for instead of some unrelated fixed size. */
    private fun customOverride(id: Int, density: Int, theme: Theme?): Drawable? {
        if (InuConfig.ICON_REPLACEMENT.value != InuConfig.IconReplacementItem.CUSTOM) return null
        val packId = InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value
        if (packId.isEmpty()) return null
        val name = try {
            resources.getResourceEntryName(id)
        } catch (e: Exception) {
            return null
        }
        if (!IconPackStorage.hasOverride(packId, name)) return null
        val targetDensity = if (density != 0) density else resources.displayMetrics.densityDpi
        val original = try {
            resources.getDrawableForDensity(id, targetDensity, theme)
        } catch (e: Exception) {
            null
        }
        val width = original?.intrinsicWidth?.takeIf { it > 0 } ?: AndroidUtilities.dp(24f)
        val height = original?.intrinsicHeight?.takeIf { it > 0 } ?: width
        return IconPackStorage.getOverrideDrawable(packId, name, width, height, targetDensity)
    }

    private fun getConversion(icon: Int): Int {
        return when (InuConfig.ICON_REPLACEMENT.value) {
            InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(icon)
            else -> icon
        }
    }
}
