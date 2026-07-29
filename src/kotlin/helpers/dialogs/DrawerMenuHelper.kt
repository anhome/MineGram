package desu.mintgram.helpers.dialogs

import android.os.Bundle
import desu.mintgram.InuConfig
import desu.mintgram.PluginsBridge
import desu.mintgram.helpers.feed.FeedChatHelper
import desu.mintgram.helpers.menu.DrawerMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.AccountFrozenAlert
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.CallLogActivity
import org.telegram.ui.ChatActivity
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.GroupCreateActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.ProxyListActivity
import org.telegram.ui.QrActivity
import org.telegram.ui.SettingsActivity

/**
 * Resolves [InuConfig.DRAWER_MENU_ITEMS] into concrete, renderable rows and dispatches their
 * clicks. The `MainMenuHelper` equivalent — pure resolution/dispatch, no view state.
 */
object DrawerMenuHelper {

    sealed class Row {
        data class Static(val item: DrawerMenuConfig.Item, val label: CharSequence, val icon: Int) : Row()
        data class Bot(val bot: TLRPC.TL_attachMenuBot) : Row()
        data object Divider : Row()
    }

    @JvmStatic
    fun resolveOrderedItems(account: Int): List<Row> {
        val out = ArrayList<Row>()
        for (entry in InuConfig.DRAWER_MENU_ITEMS.value) {
            if (!entry.enabled) continue
            when (entry.item) {
                DrawerMenuConfig.Item.DIVIDER -> out.add(Row.Divider)

                DrawerMenuConfig.Item.BOTS -> {
                    val menuBots = MediaDataController.getInstance(account).attachMenuBots
                    menuBots?.bots?.forEach { bot ->
                        if (bot.show_in_side_menu) out.add(Row.Bot(bot))
                    }
                }

                // Mirrors the overflow menu: a pending compose-draft swaps "New Group" for "New Message".
                DrawerMenuConfig.Item.NEW_GROUP -> {
                    if (DialogsFabHelper.hasNewMessage()) {
                        out.add(Row.Static(entry.item, LocaleController.getString(R.string.NewMessageTitle), R.drawable.menu_topic_add))
                    } else {
                        out.add(Row.Static(entry.item, LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups))
                    }
                }

                else -> out.add(Row.Static(entry.item, LocaleController.getString(entry.item.labelRes), entry.item.iconRes))
            }
        }
        return out
    }

    @JvmStatic
    fun openOwnProfile(nav: INavigationLayout) {
        val account = UserConfig.selectedAccount
        val args = Bundle()
        args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
        args.putBoolean("my_profile", true)
        nav.presentFragment(ProfileActivity(args))
    }

    @JvmStatic
    fun onClick(row: Row, nav: INavigationLayout, drawerLayoutContainer: DrawerLayoutContainer) {
        val account = UserConfig.selectedAccount
        val close = { drawerLayoutContainer.inu_drawer?.closeDrawer(false) }

        when (row) {
            is Row.Bot -> {
                val activity = LaunchActivity.instance ?: return
                LaunchActivity.showAttachMenuBot(activity, account, row.bot, null, true)
                close()
            }

            Row.Divider -> {}

            is Row.Static -> when (row.item) {
                DrawerMenuConfig.Item.PROFILE -> {
                    openOwnProfile(nav)
                    close()
                }

                DrawerMenuConfig.Item.NEW_GROUP -> {
                    if (DialogsFabHelper.hasNewMessage()) {
                        (nav.lastFragment as? DialogsActivity)?.openWriteContacts()
                        close()
                    } else if (MessagesController.getInstance(account).isFrozen) {
                        AccountFrozenAlert.show(account)
                    } else {
                        nav.presentFragment(GroupCreateActivity(Bundle()))
                        close()
                    }
                }

                DrawerMenuConfig.Item.CONTACTS -> {
                    val args = Bundle()
                    args.putBoolean("needPhonebook", true)
                    nav.presentFragment(ContactsActivity(args))
                    close()
                }

                DrawerMenuConfig.Item.CALLS -> {
                    nav.presentFragment(CallLogActivity())
                    close()
                }

                DrawerMenuConfig.Item.SAVED_MESSAGES -> {
                    // ChatActivity expects user_id, not dialog_id
                    val args = Bundle()
                    args.putLong("user_id", UserConfig.getInstance(account).getClientUserId())
                    nav.presentFragment(ChatActivity(args))
                    close()
                }

                DrawerMenuConfig.Item.FEED -> {
                    (nav.lastFragment)?.let { FeedChatHelper.open(it) }
                    close()
                }

                DrawerMenuConfig.Item.QR -> {
                    nav.lastFragment?.let { QrActivity.openCameraScanActivity(it) }
                    close()
                }

                DrawerMenuConfig.Item.PROXY -> {
                    nav.presentFragment(ProxyListActivity())
                    close()
                }

                DrawerMenuConfig.Item.SETTINGS -> {
                    nav.presentFragment(SettingsActivity())
                    close()
                }

                DrawerMenuConfig.Item.PLUGINS -> {
                    PluginsBridge.pluginsActivityFactory?.invoke()?.let { nav.presentFragment(it) }
                    close()
                }

                DrawerMenuConfig.Item.BOTS, DrawerMenuConfig.Item.DIVIDER -> close()
            }
        }
    }
}
