package desu.mintgram.ui.pillstack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillType
import desu.mintgram.ui.settings.pillstack.PillStackSettingsActivity
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.recorder.Weather

/** Current weather via the stock in-app-bot mechanism ([Weather.fetch]) — see [BasePill]. */
class WeatherPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        setPadding(dp(8f), 0, dp(8f), 0)
    }
    private val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val textView = AnimatedTextView(context, true, true, true).apply {
        setTextSize(dp(13f).toFloat())
        setTypeface(org.telegram.messenger.AndroidUtilities.bold())
        setIncludeFontPadding(false)
        adaptWidth = true
    }

    override fun getRefreshInterval(): Long = 1_200_000L
    override fun getPillId(): Int = PillType.WEATHER.id

    init {
        addView(layout, LayoutHelper.createFrame(-2, 28, (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL))
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        NotificationCenter.listenEmojiLoading(textView)
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL))
        loadingTargetView = layout
        updateColors()
        ScaleStateListAnimator.apply(layout)
        Weather.getCached()?.let { setData(it, false) }
    }

    override fun onPillClicked() {
        if (!hasLocationPermission()) {
            LaunchActivity.getSafeLastFragment()?.presentFragment(PillStackSettingsActivity())
            return
        }
        onPillLongClicked()
    }

    /** Only checks — never itself triggers the system permission prompt. That happens exclusively
     * from [PillStackSettingsActivity], per the user's explicit ask (no surprise prompt on the main screen). */
    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPillLongClicked(): Boolean {
        val fragment: BaseFragment = LaunchActivity.getSafeLastFragment() ?: return false
        ItemOptions.makeOptions(fragment, this)
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
            .setDrawScrim(false)
            .setDimAlpha(0)
            .show()
        return true
    }

    override fun onUpdateData(force: Boolean) {
        if (!hasLocationPermission()) {
            setPermissionNeededState(force)
            return
        }
        startLoading()
        Weather.fetch(false) { state -> postDelayed({ if (state != null) setData(state, true) else setErrorState(true) }, 300) }
    }

    private fun setPermissionNeededState(animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.setImageResource(R.drawable.filled_location)
        iconView.visibility = VISIBLE
        textView.setText(LocaleController.getString(R.string.InuWeatherPill), animated)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (PillStackConfig.checkAndClearPendingUpdate(getPillId()) || Weather.getCached() == null || isRefreshDue()) {
            onUpdateData(true)
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.pillStackSettingsChanged && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId())
            onUpdateData(true)
        }
    }

    private fun setErrorState(animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.setImageResource(R.drawable.msg_retry)
        iconView.visibility = VISIBLE
        textView.setText(LocaleController.getString(R.string.Retry), animated)
    }

    private fun setData(state: Weather.State, animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        val iconRes = weatherIconRes(state.emoji)
        if (iconRes != 0) {
            iconView.setImageResource(iconRes)
            iconView.visibility = VISIBLE
            textView.setText(state.getTemperature(), animated)
        } else {
            iconView.visibility = GONE
            textView.setText(Emoji.replaceEmoji("${state.emoji} ${state.getTemperature()}", textView.paint.fontMetricsInt, true), animated)
        }
    }

    private fun weatherIconRes(emoji: String?): Int = when (emoji) {
        "☀" -> R.drawable.weather_sunny
        "☁" -> R.drawable.weather_cloudy
        "⚡", "⛈" -> R.drawable.weather_thunderstorm
        "⛅", "🌤" -> R.drawable.weather_partly_cloudy
        "❄", "🌨" -> R.drawable.weather_snowy
        "🌓", "🌔", "🌖", "🌗", "🌚", "🌛", "🌜", "🌝" -> R.drawable.weather_night
        "🌦", "🌧" -> R.drawable.weather_rainy
        "😶‍🌫" -> R.drawable.weather_foggy
        else -> 0
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
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
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
