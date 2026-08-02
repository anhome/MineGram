package desu.mintgram.ui.drawer

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import desu.mintgram.helpers.dialogs.DrawerMenuHelper
import desu.mintgram.helpers.menu.DrawerMenuConfig
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DividerCell
import org.telegram.ui.Components.LayoutHelper

/**
 * Plain scrollable list of drawer menu rows. Rebuilt wholesale on every open/account-switch/
 * config-change from [DrawerMenuHelper.resolveOrderedItems] — no diffing, matching exteraGram's
 * own choice for this section.
 */
class DrawerMenuView(context: Context) : ScrollView(context) {

    private val container: LinearLayout

    var nav: INavigationLayout? = null
    var drawerLayoutContainer: DrawerLayoutContainer? = null
    var onProxySwitchToggled: ((Boolean) -> Unit)? = null

    init {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun rebuild(account: Int) {
        container.removeAllViews()
        val navigation = nav ?: return
        val dlc = drawerLayoutContainer ?: return

        for (row in DrawerMenuHelper.resolveOrderedItems(account)) {
            val view: View = when (row) {
                is DrawerMenuHelper.Row.Divider -> DividerCell(context)

                is DrawerMenuHelper.Row.Bot -> DrawerActionCell(context).apply {
                    setBot(row.bot)
                    setOnClickListener { DrawerMenuHelper.onClick(row, navigation, dlc) }
                }

                is DrawerMenuHelper.Row.Static -> if (row.item == DrawerMenuConfig.Item.PROXY) {
                    DrawerProxyCell(context).apply {
                        bind(row.label, row.icon, row.item)
                        setSwitchVisible(SharedConfig.proxyList.isNotEmpty())
                        setChecked(SharedConfig.isProxyEnabled())
                        onSwitchToggled = onProxySwitchToggled
                        setOnClickListener { DrawerMenuHelper.onClick(row, navigation, dlc) }
                    }
                } else {
                    DrawerActionCell(context).apply {
                        // Only Settings (old stable id 8) carries the pending-suggestion error badge.
                        val id = if (row.item == DrawerMenuConfig.Item.SETTINGS) 8 else 0
                        setTextAndIcon(id, row.label, row.icon, row.item)
                        setOnClickListener { DrawerMenuHelper.onClick(row, navigation, dlc) }
                    }
                }
            }
            if (view !is DividerCell) {
                view.isClickable = true
                view.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            }
            container.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }
}
