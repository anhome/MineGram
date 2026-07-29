package desu.mintgram.ui.pillstack

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import desu.mintgram.helpers.pillstack.PillStackConfig
import org.telegram.ui.Components.CubicBezierInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows one [BasePill] at a time; vertical swipe cycles to the next/previous one in [pills].
 * Mirrors exteraGram's `PillStackView` gesture model exactly (this is the one piece of the port
 * that's genuinely a custom widget, not a repurposed stock mechanism).
 */
class PillStackView(context: Context) : FrameLayout(context) {
    private val pills = ArrayList<BasePill>()
    private var currentIndex = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private var isSwipingUp = false
    private var currentSwipeProgress = 0f
    private var currentAnimator: ValueAnimator? = null
    private var maybeClick = false
    private var longClickPerformed = false
    private var visibilityFactor = -1f
    private var stackOnScreen = true

    private val longPressRunnable = Runnable {
        if (!maybeClick || isSwiping || pills.isEmpty()) return@Runnable
        longClickPerformed = pills[currentIndex].onPillLongClicked()
        if (longClickPerformed) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        clipChildren = false
    }

    fun addPill(pill: BasePill) {
        pills.add(pill)
        addView(pill)
        if (pills.size - 1 != currentIndex) {
            pill.alpha = 0f
            pill.scaleX = 0.8f
            pill.scaleY = 0.8f
            pill.visibility = GONE
        } else {
            pill.visibility = VISIBLE
            pill.onPillSelected()
        }
        pill.onStackVisibilityChanged(stackOnScreen)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (stackOnScreen == isVisible) return
        stackOnScreen = isVisible
        pills.forEach { it.onStackVisibilityChanged(isVisible) }
    }

    fun getPillsCount(): Int = pills.size

    fun setCurrentIndex(index: Int) {
        if (index < 0 || index >= pills.size || index == currentIndex) return
        pills[currentIndex].apply {
            visibility = GONE
            onPillUnselected()
        }
        currentIndex = index
        pills[index].apply {
            visibility = VISIBLE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationY = 0f
            onPillSelected()
        }
        requestLayout()
    }

    fun clearPills() {
        if (pills.isNotEmpty() && currentIndex < pills.size) {
            pills[currentIndex].onPillUnselected()
        }
        pills.clear()
        removeAllViews()
        currentIndex = 0
    }

    fun setVisibilityFactor(factor: Float) {
        if (visibilityFactor == factor) return
        visibilityFactor = factor
        if (factor > 0.01f) {
            if (visibility != VISIBLE) visibility = VISIBLE
            alpha = visibilityFactor
            scaleX = org.telegram.messenger.AndroidUtilities.lerp(0.6f, 1f, visibilityFactor)
            scaleY = org.telegram.messenger.AndroidUtilities.lerp(0.6f, 1f, visibilityFactor)
        } else {
            visibility = GONE
        }
    }

    fun updateColors() {
        pills.forEach { it.updateColors() }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pills.isEmpty()) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                if (abs(dy) > touchSlop && abs(dy) > abs(dx) && pills.size > 1) {
                    isSwiping = true
                    currentAnimator?.cancel()
                    val base = if (isSwipingUp) -(currentSwipeProgress * height) else height * currentSwipeProgress
                    startY = ev.rawY - base
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pills.isEmpty()) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                isSwiping = false
                maybeClick = true
                longClickPerformed = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                val pill = pills[currentIndex]
                pill.isPressed = true
                pill.drawableHotspotChanged(ev.x - pill.left, ev.y - pill.top)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - startX
                val dy = ev.rawY - startY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    maybeClick = false
                    removeCallbacks(longPressRunnable)
                }
                if (isSwiping) {
                    handleSwipeProgress(dy)
                    return true
                }
                if (abs(dy) > touchSlop && pills.size > 1) {
                    isSwiping = true
                    currentAnimator?.cancel()
                    val base = if (isSwipingUp) -(currentSwipeProgress * height) else height * currentSwipeProgress
                    startY = ev.rawY - base
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return super.onTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (isSwiping) {
                    finishSwipe(ev.rawY - startY)
                    isSwiping = false
                } else if (maybeClick && !longClickPerformed) {
                    pills[currentIndex].onPillClicked()
                }
                pills.forEach { it.isPressed = false }
                maybeClick = false
                return true
            }
            else -> {
                removeCallbacks(longPressRunnable)
                return super.onTouchEvent(ev)
            }
        }
    }

    private fun handleSwipeProgress(dy: Float) {
        val h = height
        if (pills.size <= 1 || h <= 0) return
        isSwipingUp = dy < 0f
        val progress = abs(dy) / h
        val next = if (isSwipingUp) currentIndex + 1 else currentIndex - 1
        currentSwipeProgress = if (!PillStackConfig.infiniteScrolling && (next >= pills.size || next < 0)) progress else min(progress, 1f)
        applyProgress(currentSwipeProgress, isSwipingUp)
    }

    private fun applyProgress(f: Float, up: Boolean) {
        val current = pills[currentIndex]
        var next = if (up) currentIndex + 1 else currentIndex - 1
        if (PillStackConfig.infiniteScrolling) {
            if (next >= pills.size) next = 0
            if (next < 0) next = pills.size - 1
        }
        for (i in pills.indices) {
            if (i != currentIndex && i != next && pills[i].visibility != GONE) pills[i].visibility = GONE
        }
        if (!PillStackConfig.infiniteScrolling && (next >= pills.size || next < 0)) {
            var h = height * (1.0 - 1.0 / (f * 0.18f + 1.0)).toFloat()
            if (up) h = -h
            current.translationY = h
            current.alpha = 1f
            return
        }
        val fMin = min(f, 1f)
        val nextPill = pills[next]
        if (nextPill.visibility != VISIBLE) nextPill.visibility = VISIBLE
        var h = height * fMin
        if (up) h = -h
        current.translationY = h
        current.alpha = 1f - fMin
        val shrink = 1f - 0.2f * fMin
        current.scaleX = shrink
        current.scaleY = shrink
        val grow = 0.8f + 0.2f * fMin
        nextPill.scaleX = grow
        nextPill.scaleY = grow
        nextPill.alpha = fMin
        var startTranslation = height.toFloat()
        if (!up) startTranslation = -startTranslation
        nextPill.translationY = startTranslation - fMin * startTranslation
    }

    private fun finishSwipe(dy: Float) {
        val h = height
        if (h <= 0) {
            cancelSwipe(isSwipingUp)
            return
        }
        val threshold = h * 0.25f
        var canAdvance = true
        if (!PillStackConfig.infiniteScrolling) {
            val next = if (isSwipingUp) currentIndex + 1 else currentIndex - 1
            if (next >= pills.size || next < 0) canAdvance = false
        }
        if (abs(dy) > threshold && canAdvance) animateToNextPill(isSwipingUp) else cancelSwipe(isSwipingUp)
    }

    private fun animateToNextPill(up: Boolean) {
        currentAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(currentSwipeProgress, 1f)
        currentAnimator = animator
        animator.duration = 250
        animator.interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
        animator.addUpdateListener { applyProgress(it.animatedValue as Float, up) }
        var cancelled = false
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                val old = pills[currentIndex]
                old.visibility = GONE
                old.isPressed = false
                old.scaleX = 1f
                old.scaleY = 1f
                old.onPillUnselected()
                currentIndex = if (up) currentIndex + 1 else currentIndex - 1
                if (PillStackConfig.infiniteScrolling) {
                    if (currentIndex >= pills.size) currentIndex = 0
                    if (currentIndex < 0) currentIndex = pills.size - 1
                }
                for (i in pills.indices) {
                    if (i != currentIndex) pills[i].visibility = GONE
                }
                val selected = pills[currentIndex]
                selected.visibility = VISIBLE
                selected.scaleX = 1f
                selected.scaleY = 1f
                selected.translationY = 0f
                selected.alpha = 1f
                selected.onPillSelected()
                currentSwipeProgress = 0f
                PillStackConfig.lastActivePillId = selected.getPillId()
            }
        })
        animator.start()
    }

    private fun cancelSwipe(up: Boolean) {
        currentAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(currentSwipeProgress, 0f)
        currentAnimator = animator
        animator.duration = 200
        animator.addUpdateListener { applyProgress(it.animatedValue as Float, up) }
        var cancelled = false
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                for (i in pills.indices) {
                    if (i != currentIndex) {
                        pills[i].apply {
                            visibility = GONE
                            isPressed = false
                            scaleX = 1f
                            scaleY = 1f
                        }
                    }
                }
                pills[currentIndex].apply {
                    translationY = 0f
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                }
                currentSwipeProgress = 0f
            }
        })
        animator.start()
    }
}
