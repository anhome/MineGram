package desu.mintgram.ui.pillstack

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.mintgram.helpers.pillstack.ExchangeRates
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillStackCurrencies
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference

/** BTC/USD/GRAM(TON) pills — one live [ExchangeRates] value shown against a user-pickable target currency. */
abstract class RatePill(
    context: Context,
    resourcesProvider: Theme.ResourcesProvider?,
    private val baseCurrency: String,
    private val scale: Int,
    private val iconResId: Int,
    pillBackground: android.graphics.drawable.Drawable,
) : BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    /** Shared across all instances of the same pill type (BTC/USD/GRAM) so switching screens doesn't refetch. */
    class RateCache {
        val cachedPrice = AtomicReference<String?>()
        val cachedCurrency = AtomicReference<String?>()
    }

    private val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        setPadding(dp(8f), 0, dp(8f), 0)
        background = pillBackground
    }
    private val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val textView = AnimatedTextView(context, true, true, true).apply {
        setTextSize(dp(13f).toFloat())
        setIncludeFontPadding(false)
        setTypeface(org.telegram.messenger.AndroidUtilities.bold())
        adaptWidth = true
    }
    private var requestInFlight = false

    protected abstract fun cache(): RateCache
    abstract fun getTargetSelection(): String
    abstract fun setTargetSelection(value: String)
    open fun getTargetCurrencies(): Array<String> = PillStackCurrencies.TARGET_CURRENCIES

    override fun getRefreshInterval(): Long = 300_000L

    init {
        addView(layout, LayoutHelper.createFrame(-2, 28, Gravity.CENTER_VERTICAL or (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)))
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL))
        loadingTargetView = layout
        updateColors()
        ScaleStateListAnimator.apply(layout)
        cache().cachedPrice.get()?.let { setData(it, false) }
    }

    override fun onPillClicked() {
        if (iconView.visibility == VISIBLE && textView.text?.toString() == LocaleController.getString(R.string.Retry)) {
            onUpdateData(true)
        } else {
            onPillLongClicked()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (PillStackConfig.checkAndClearPendingUpdate(getPillId()) || cache().cachedPrice.get() == null || isRefreshDue()) {
            onUpdateData(true)
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, getPillId()) && getTargetSelection() == "AUTO") {
            PillStackConfig.checkAndClearPendingUpdate(getPillId())
            onUpdateData(true)
        }
    }

    override fun onPillLongClicked(): Boolean {
        val fragment: BaseFragment = LaunchActivity.getSafeLastFragment() ?: return false
        val options = ItemOptions.makeOptions(fragment, this, true)
        val swipeback = options.makeSwipeback()
        swipeback.add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::closeSwipeback)
        swipeback.addGap()
        val targetSelection = getTargetSelection()
        for (code in getTargetCurrencies()) {
            swipeback.addChecked(code.equals(targetSelection, ignoreCase = true), PillStackCurrencies.getTargetCurrencyLabel(code)) {
                options.dismiss()
                if (!code.equals(targetSelection, ignoreCase = true)) {
                    setTargetSelection(code)
                    onUpdateData(false)
                }
            }
        }
        val currencyItem = ActionBarMenuSubItem(options.context, false, false, resourcesProvider)
        currencyItem.setTextAndIcon(LocaleController.getString(R.string.InuCryptoPillTargetCurrency), R.drawable.msg_language)
        currencyItem.setSubtext(PillStackCurrencies.getTargetCurrencyLabel(getTargetSelection()))
        currencyItem.setItemHeight(56)
        currencyItem.setOnClickListener { options.openSwipeback(swipeback) }
        options.addView(currencyItem)
            .addGap()
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
            .setDrawScrim(false)
            .setGravity(if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)
            .setDimAlpha(0)
            .show()
        return true
    }

    override fun onUpdateData(force: Boolean) {
        val target = ExchangeRates.resolveTargetCurrency(getTargetSelection())
        var cached = cache().cachedPrice.get()
        if (target != cache().cachedCurrency.get()) cached = null
        if (!force && cached != null && !isRefreshDue()) {
            setData(cached, false)
            return
        }
        if (requestInFlight) return
        requestInFlight = true
        if (force) animateSizeChange()
        startLoading()
        if (cached == null && cache().cachedPrice.get() == null) {
            iconView.visibility = GONE
            textView.visibility = GONE
        } else {
            iconView.setImageResource(iconResId)
            iconView.visibility = VISIBLE
            textView.visibility = VISIBLE
        }
        if (force) ExchangeRates.clearCache()
        ExchangeRates.fetch { state ->
            requestInFlight = false
            val rate = state?.getRate(baseCurrency, target)
            if (rate == null) {
                val fallback = cache().cachedPrice.get()
                if (fallback != null) setData(fallback, true) else setErrorState(true)
                return@fetch
            }
            val price = formatPrice(rate, target)
            cache().cachedPrice.set(price)
            cache().cachedCurrency.set(target)
            setData(price, true)
            markDataUpdated()
        }
    }

    private fun formatPrice(amount: BigDecimal, currency: String): String {
        PillStackCurrencies.formatFiatPrice(amount, currency)?.let { return it }
        return "${amount.setScale(scale, RoundingMode.HALF_UP).toPlainString()} $currency"
    }

    private fun setErrorState(animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.setImageResource(R.drawable.msg_retry)
        iconView.visibility = VISIBLE
        textView.setText(LocaleController.getString(R.string.Retry), animated)
        textView.visibility = VISIBLE
    }

    private fun setData(text: String, animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.setImageResource(iconResId)
        iconView.visibility = VISIBLE
        textView.setText(text, animated)
        textView.visibility = VISIBLE
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        textView.setTextColor(-1)
        iconView.colorFilter = android.graphics.PorterDuffColorFilter(-1, android.graphics.PorterDuff.Mode.MULTIPLY)
        updateLoadingColors()
    }

    override fun updateLoadingColors() {
        loadingDrawable?.setColors(Theme.multAlpha(-1, 0.1f), Theme.multAlpha(-1, 0.3f))
    }
}
