package desu.mintgram.ui.pillstack

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LoadingDrawable

/**
 * Base for a single pill in [PillStackView]. Mirrors exteraGram's `BasePill` — per-pill-id
 * refresh throttling shared across all live instances (so switching accounts/reattaching doesn't
 * spam the pill's data source), loading-shimmer overlay, and a helper to animate size changes
 * when text content changes width.
 */
abstract class BasePill(context: Context, protected val resourcesProvider: Theme.ResourcesProvider?) : FrameLayout(context) {
    companion object {
        private val globalLastUpdateTimes = HashMap<Int, Long>()
    }

    protected var loading = false
    protected var loadingDrawable: LoadingDrawable? = null
    protected var loadingTargetView: View? = null
    private val rectF = RectF()
    private var stackVisible = true
    private val autoRefreshRunnable = Runnable {
        onUpdateData(false)
        scheduleNextUpdate()
    }

    abstract fun getPillId(): Int
    abstract fun getRefreshInterval(): Long
    abstract fun onPillClicked()
    abstract fun onPillLongClicked(): Boolean
    open fun onPillSelected() {}
    open fun onPillUnselected() {}
    abstract fun onUpdateData(force: Boolean)
    abstract fun updateColors()

    init {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL,
        )
        clipChildren = false
        clipToPadding = false
    }

    private fun scheduleNextUpdate() {
        removeCallbacks(autoRefreshRunnable)
        if (stackVisible) {
            val interval = getRefreshInterval()
            if (interval > 0) postDelayed(autoRefreshRunnable, interval)
        }
    }

    fun isRefreshDue(): Boolean {
        val interval = getRefreshInterval()
        if (interval <= 0) return true
        val last = globalLastUpdateTimes[getPillId()] ?: 0L
        return last == 0L || SystemClock.elapsedRealtime() - last >= interval
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (stackVisible) {
            val interval = getRefreshInterval()
            if (interval > 0) {
                val now = SystemClock.elapsedRealtime()
                val last = globalLastUpdateTimes[getPillId()] ?: 0L
                if (last != 0L) {
                    val elapsed = now - last
                    if (elapsed < interval) {
                        postDelayed(autoRefreshRunnable, interval - elapsed)
                        return
                    }
                }
                autoRefreshRunnable.run()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoRefreshRunnable)
    }

    fun onStackVisibilityChanged(visible: Boolean) {
        if (stackVisible == visible) return
        stackVisible = visible
        if (!visible) {
            removeCallbacks(autoRefreshRunnable)
        } else if (getRefreshInterval() > 0) {
            if (isRefreshDue()) onUpdateData(false)
            scheduleNextUpdate()
        }
    }

    fun markDataUpdated() {
        globalLastUpdateTimes[getPillId()] = SystemClock.elapsedRealtime()
        scheduleNextUpdate()
    }

    fun startLoading() {
        loading = true
        var drawable = loadingDrawable
        if (drawable == null) {
            drawable = LoadingDrawable(resourcesProvider)
            loadingDrawable = drawable
            drawable.callback = this
            drawable.setGradientScale(2f)
            drawable.setRadiiDp(14f)
            updateLoadingColors()
        }
        drawable.reset()
        drawable.resetDisappear()
        drawable.alpha = 255
        invalidate()
    }

    fun animateSizeChange() {
        val parent = parent
        if (isLaidOut && visibility == VISIBLE && parent != null && parent.parent is ViewGroup) {
            TransitionManager.beginDelayedTransition(
                parent.parent as ViewGroup,
                TransitionSet().addTransition(ChangeBounds()).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT),
            )
        }
    }

    fun stopLoading() {
        loading = false
        loadingDrawable?.disappear()
    }

    open fun updateLoadingColors() {
        loadingDrawable?.let {
            val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText)
            it.setColors(Theme.multAlpha(color, 0.05f), Theme.multAlpha(color, 0.15f))
        }
    }

    fun getThemedColor(key: Int): Int = Theme.getColor(key, resourcesProvider)
    fun getThemedColor(key: Int, alpha: Float): Int = Theme.multAlpha(getThemedColor(key), alpha)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val drawable = loadingDrawable ?: return
        if (drawable.alpha > 0 || !drawable.isDisappearing) {
            val target = loadingTargetView ?: this
            rectF.set(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat())
            drawable.setBounds(rectF)
            drawable.draw(canvas)
            invalidate()
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return who === loadingDrawable || super.verifyDrawable(who)
    }
}
