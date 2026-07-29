package desu.mintgram.ui.pillstack

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProxyListActivity

/** Current proxy status/ping — no network of its own, purely reads stock proxy state. */
class ProxyPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        setPadding(dp(8f), 0, dp(10f), 0)
    }
    private val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val textView = AnimatedTextView(context, true, true, true).apply {
        setTextSize(dp(13f).toFloat())
        setIncludeFontPadding(false)
        setTypeface(org.telegram.messenger.AndroidUtilities.bold())
        adaptWidth = true
    }
    private var lastAccount = UserConfig.selectedAccount

    override fun getRefreshInterval(): Long = 30_000L
    override fun getPillId(): Int = PillType.PROXY.id

    init {
        addView(layout, LayoutHelper.createFrame(-2, 28, Gravity.CENTER_VERTICAL or (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)))
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 2f, 0f))
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL))
        loadingTargetView = layout
        updateColors()
        ScaleStateListAnimator.apply(layout)
        onUpdateData(false)
    }

    override fun onUpdateData(force: Boolean) {
        val proxyEnabled = SharedConfig.isProxyEnabled()
        val connectionState = ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState
        val connected = connectionState == ConnectionsManager.ConnectionStateConnected || connectionState == ConnectionsManager.ConnectionStateUpdating
        val proxy = SharedConfig.currentProxy
        val text: String
        if (!proxyEnabled || proxy == null) {
            iconView.setImageResource(R.drawable.drawer_proxy_off)
            text = LocaleController.getString(R.string.Proxy)
            stopLoading()
        } else if (connected) {
            val ping = Utilities.clamp(proxy.ping, 9999L, 0L)
            iconView.setImageResource(R.drawable.drawer_proxy_on)
            text = if (ping > 0) LocaleController.formatString(R.string.InuProxyPingShort, ping) else LocaleController.getString(R.string.MenuProxyConnected)
            stopLoading()
        } else {
            iconView.setImageResource(R.drawable.drawer_proxy_off)
            text = LocaleController.getString(R.string.MenuProxyConnecting)
            startLoading()
        }
        if (force || textView.text?.toString() != text) {
            if (force) animateSizeChange()
            textView.setText(text, force)
        }
        updateColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onUpdateData(true)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged)
        lastAccount = UserConfig.selectedAccount
        NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.didUpdateConnectionState)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged)
        NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.didUpdateConnectionState) {
            onUpdateData(true)
        }
    }

    override fun onPillClicked() {
        LaunchActivity.getSafeLastFragment()?.presentFragment(ProxyListActivity())
    }

    override fun onPillLongClicked(): Boolean {
        val fragment: BaseFragment = LaunchActivity.getSafeLastFragment() ?: return false
        ItemOptions.makeOptions(fragment, this)
            .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings)) { fragment.presentFragment(ProxyListActivity()) }
            .setDrawScrim(false)
            .setDimAlpha(0)
            .show()
        return true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun updateColors() {
        val proxyEnabled = SharedConfig.isProxyEnabled()
        val connectionState = ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState
        val connected = connectionState == ConnectionsManager.ConnectionStateConnected || connectionState == ConnectionsManager.ConnectionStateUpdating
        val color = if (proxyEnabled && SharedConfig.currentProxy != null && connected) {
            getThemedColor(Theme.key_windowBackgroundWhiteGreenText)
        } else {
            getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        }
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f),
        )
        textView.setTextColor(color)
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        updateLoadingColors()
    }
}
