package desu.mintgram.helpers.menu

import org.telegram.messenger.R

class DrawerMenuConfig(key: String) : MenuOrderConfig<DrawerMenuConfig.Item>(key, Item.entries, OFF_BY_DEFAULT) {
    enum class Item(
        override val key: String,
        override val labelRes: Int,
        override val iconRes: Int,
    ) : MenuOrderItem {
        PROFILE("profile", R.string.MyProfile, R.drawable.left_status_profile),
        BOTS("bots", R.string.InuDrawerAttachBots, R.drawable.msg_bot),
        DIVIDER("divider", R.string.InuDrawerDivider, R.drawable.iv_divider),
        NEW_GROUP("new_group", R.string.NewGroup, R.drawable.msg_groups),
        CONTACTS("contacts", R.string.Contacts, R.drawable.msg_contacts),
        CALLS("calls", R.string.Calls, R.drawable.msg_calls),
        SAVED_MESSAGES("saved_messages", R.string.SavedMessages, R.drawable.msg_saved),
        FEED("feed", R.string.InuFeed, R.drawable.ic_feed),
        QR("qr", R.string.ScanQrCode, R.drawable.msg_qrcode),
        PROXY("proxy", R.string.ProxySettings, R.drawable.outline_shield_check),
        SETTINGS("settings", R.string.Settings, R.drawable.msg_settings),
        PLUGINS("plugins", R.string.InuPlugins, R.drawable.msg_plugins);

        companion object {
            private val byKey = entries.associateBy { it.key }
            fun forKey(k: String): Item? = byKey[k]
        }
    }

    override fun itemByKey(key: String): Item? = Item.forKey(key)

    companion object {
        private val OFF_BY_DEFAULT = setOf(Item.PLUGINS)
    }
}
