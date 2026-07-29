package desu.mintgram.ui.pillstack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.BatteryManager
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity

/** Device battery level/charging state — reads the sticky `ACTION_BATTERY_CHANGED` broadcast,
 * no polling needed since the system re-delivers it on every real change. */
class BatteryPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider) {

    private val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        setPadding(dp(6f), 0, dp(8f), 0)
    }
    private val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val textView = AnimatedTextView(context, true, true, true).apply {
        setTextSize(dp(13f).toFloat())
        setTypeface(org.telegram.messenger.AndroidUtilities.bold())
        setIncludeFontPadding(false)
        adaptWidth = true
    }

    private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return
            val percent = (level * 100) / scale
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            setData(percent)
        }
    }

    override fun getRefreshInterval(): Long = 0L // no polling - the sticky broadcast pushes updates
    override fun getPillId(): Int = PillType.BATTERY.id

    init {
        addView(layout, LayoutHelper.createFrame(-2, 28, Gravity.CENTER_VERTICAL or (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)))
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f))
        iconView.setImageResource(R.drawable.msg2_battery_solar)
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL))
        loadingTargetView = layout
        updateColors()
        ScaleStateListAnimator.apply(layout)
    }

    override fun onUpdateData(force: Boolean) {
        val sticky = ApplicationLoader.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(null, sticky)
    }

    private fun setData(percent: Int) {
        val text = LocaleController.formatString(R.string.InuBatteryPillPercent, percent)
        if (textView.text?.toString() != text) {
            animateSizeChange()
            textView.setText(text, true)
        }
        updateColors()
        markDataUpdated()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ApplicationLoader.applicationContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            ApplicationLoader.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    override fun onPillClicked() {
        try {
            LaunchActivity.instance?.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        } catch (_: Exception) {
        }
    }

    override fun onPillLongClicked(): Boolean = false

    override fun updateColors() {
        val color = if (charging) {
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
