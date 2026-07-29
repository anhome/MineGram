package desu.mintgram.ui.settings

import desu.mintgram.InuConfig
import desu.mintgram.helpers.menu.DrawerMenuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class DrawerMenuOrderActivity : MenuOrderActivity<DrawerMenuConfig.Item>() {
    override val config get() = InuConfig.DRAWER_MENU_ITEMS
    override val infoStringRes = R.string.InuDrawerMenuOrderInfo
    override val headerStringRes = R.string.InuDrawerMenuItems
    override val resetStringRes = R.string.InuDrawerMenuReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDrawerMenuOrder)
}
