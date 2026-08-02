package desu.mintgram.helpers.icons

import android.graphics.drawable.Drawable
import desu.mintgram.InuConfig
import desu.mintgram.helpers.menu.DrawerMenuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader

/** Resolves ExteraGram/AyuGram icon-pack aliases for Mintgram's configurable drawer rows. */
object DrawerIconPackHelper {
    private val aliases = mapOf(
        DrawerMenuConfig.Item.PROFILE to listOf(
            "left_status_profile", "msg_openprofile", "verified_profile",
        ),
        DrawerMenuConfig.Item.BOTS to listOf(
            "msg_bot", "msg_bots", "filter_bots",
        ),
        DrawerMenuConfig.Item.NEW_GROUP to listOf(
            "msg_groups", "msg_filled_menu_groups", "msg_groups_create", "filled_video_group",
        ),
        DrawerMenuConfig.Item.CONTACTS to listOf(
            "msg_contacts", "msg_filled_menu_users", "msg_contact_add", "filter_contacts",
        ),
        DrawerMenuConfig.Item.CALLS to listOf(
            "msg_calls", "msg_call_earpiece", "msg2_call_earpiece", "msg_calls_14", "profile_phone",
        ),
        DrawerMenuConfig.Item.SAVED_MESSAGES to listOf(
            "msg_saved", "chats_saved", "msg_bookmark", "msg_stories_saved",
        ),
        DrawerMenuConfig.Item.FEED to listOf(
            "ic_feed", "msg_channel", "filter_channels", "msg_filled_menu_channels",
        ),
        DrawerMenuConfig.Item.QR to listOf(
            "msg_qrcode", "header_qr_24", "msg_qr_mini", "profile_qr_scan_24",
        ),
        DrawerMenuConfig.Item.PROXY to listOf(
            "outline_shield_check", "msg2_proxy_on", "msg2_proxy_off", "msg2_policy", "msg_secret",
        ),
        DrawerMenuConfig.Item.SETTINGS to listOf(
            "msg_settings", "etg_settings", "media_settings", "msg_photo_settings",
        ),
        DrawerMenuConfig.Item.PLUGINS to listOf(
            "msg_plugins", "etg_settings", "msg_bot", "msg_bots",
        ),
    )

    fun resolve(item: DrawerMenuConfig.Item, requestedResId: Int): Drawable? {
        if (InuConfig.ICON_REPLACEMENT.value != InuConfig.IconReplacementItem.CUSTOM) return null
        val packId = InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value
        if (packId.isEmpty()) return null

        val resources = ApplicationLoader.applicationContext.resources
        val requestedName = try {
            resources.getResourceEntryName(requestedResId)
        } catch (_: Exception) {
            null
        }
        val candidates = buildList {
            requestedName?.let(::add)
            addAll(aliases[item].orEmpty())
        }.distinct()
        val overrideName = candidates.firstOrNull { IconPackStorage.hasOverride(packId, it) }
            ?: return null

        val original = try {
            resources.getDrawable(requestedResId, null)
        } catch (_: Exception) {
            null
        }
        val width = original?.intrinsicWidth?.takeIf { it > 0 } ?: AndroidUtilities.dp(24f)
        val height = original?.intrinsicHeight?.takeIf { it > 0 } ?: width
        return IconPackStorage.getOverrideDrawable(
            packId,
            overrideName,
            width,
            height,
            resources.displayMetrics.densityDpi,
        )
    }
}
