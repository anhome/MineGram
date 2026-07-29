package desu.mintgram.ui.pillstack

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.CacheControlActivity
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Reuses stock [CacheControlActivity]'s own total-size calculation — no separate scan of our own. */
class CachePill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    companion object {
        private val lastKnownCacheSize = AtomicLong(-1)
        private var lastKnownProgress = -1f
    }

    private val calculating = AtomicBoolean(false)
    private val layout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = dp(48f)
        setPadding(dp(6f), 0, dp(8f), 0)
    }
    private val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val progressDrawable = StorageProgressDrawable(iconView)
    private val textView = AnimatedTextView(context, true, true, true).apply {
        setTextSize(dp(13f).toFloat())
        setTypeface(org.telegram.messenger.AndroidUtilities.bold())
        setIncludeFontPadding(false)
        adaptWidth = true
    }

    override fun getRefreshInterval(): Long = 180_000L
    override fun getPillId(): Int = PillType.CACHE.id

    init {
        addView(layout, LayoutHelper.createFrame(-2, 28, Gravity.CENTER_VERTICAL or (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)))
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f))
        iconView.setImageDrawable(progressDrawable)
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL))
        loadingTargetView = layout
        updateColors()
        ScaleStateListAnimator.apply(layout)
        if (lastKnownCacheSize.get() != -1L && !isRefreshDue()) {
            setData(lastKnownCacheSize.get(), lastKnownProgress, false)
        } else {
            iconView.visibility = GONE
            textView.visibility = GONE
        }
    }

    override fun onUpdateData(force: Boolean) {
        val neverCalculated = lastKnownCacheSize.get() == -1L
        if ((force || neverCalculated || isRefreshDue()) && calculating.compareAndSet(false, true)) {
            if (force || neverCalculated) CacheControlActivity.resetCalculatedTotalSIze()
            startLoading()
            ImageLoader.getInstance().checkMediaPaths {
                CacheControlActivity.calculateTotalSize { size ->
                    lastKnownCacheSize.set(size)
                    CacheControlActivity.getDeviceTotalSize { total, free ->
                        val progress = if (total > 0) (total - free) / total.toFloat() else 0f
                        lastKnownProgress = progress
                        calculating.set(false)
                        setData(size, progress, true)
                    }
                }
            }
        }
    }

    private fun setData(size: Long, progress: Float, animated: Boolean) {
        stopLoading()
        val text = org.telegram.messenger.AndroidUtilities.formatFileSize(size)
        if (animated && (textView.text == null || textView.text.toString() != text || textView.visibility == GONE)) {
            animateSizeChange()
        }
        textView.setText(text, animated)
        progressDrawable.setProgress(progress, animated)
        iconView.visibility = VISIBLE
        textView.visibility = VISIBLE
        markDataUpdated()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onUpdateData(PillStackConfig.checkAndClearPendingUpdate(getPillId()))
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

    override fun onPillClicked() {
        openCacheSettings()
    }

    override fun onPillLongClicked(): Boolean {
        val fragment: BaseFragment = LaunchActivity.getSafeLastFragment() ?: return false
        ItemOptions.makeOptions(fragment, this)
            .add(R.drawable.msg2_data, LocaleController.getString(R.string.StorageUsage)) { openCacheSettings() }
            .addGap()
            .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
            .setDrawScrim(false)
            .setDimAlpha(0)
            .show()
        return true
    }

    private fun openCacheSettings() {
        LaunchActivity.getSafeLastFragment()?.presentFragment(CacheControlActivity())
    }

    override fun updateColors() {
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f),
        )
        textView.setTextColor(color)
        progressDrawable.setColor(color)
        updateLoadingColors()
    }

    private class StorageProgressDrawable(view: View) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val rectF = RectF()
        private var progress = 0f
        private var color = 0
        private val animatedProgress = AnimatedFloat(view, 650, CubicBezierInterpolator.EASE_OUT_QUINT)

        override fun getOpacity() = -2

        fun setProgress(value: Float, animated: Boolean) {
            progress = max(0.05f, min(value, 1f))
            if (!animated) animatedProgress.force(progress)
            invalidateSelf()
        }

        fun setColor(value: Int) {
            color = value
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val width = bounds.width()
            val height = bounds.height()
            val size = min(width, height) - dp(2f)
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            rectF.set(left, top, left + size, top + size)
            val animated = animatedProgress.set(progress)
            paint.strokeWidth = dp(2f).toFloat()
            paint.color = color
            paint.alpha = 50
            canvas.drawCircle(width / 2f, height / 2f, size / 2f, paint)
            paint.alpha = 255
            canvas.drawArc(rectF, -90f, animated * 360f, false, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }
    }
}
