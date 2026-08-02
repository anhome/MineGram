package desu.mintgram.helpers.calls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.voip.VoIPService
import org.telegram.ui.Components.CubicBezierInterpolator
import java.lang.ref.WeakReference

/** One in-call launcher which smoothly reveals the recorder and soundpad controls. */
object CallToolsHelper {
    private var holderRef: WeakReference<Holder>? = null

    @JvmStatic
    fun attach(root: FrameLayout, peerName: String, callColor: Int, callColor2: Int): View {
        return attachInternal(root, peerName, callColor, callColor2, 88f, 150f, 216f)
    }

    @JvmStatic
    fun attachGroup(root: FrameLayout, title: String, callColor: Int, callColor2: Int): View {
        return attachInternal(root, title, callColor, callColor2, 132f, 194f, 260f)
    }

    private fun attachInternal(
        root: FrameLayout,
        peerName: String,
        callColor: Int,
        callColor2: Int,
        launcherBottomMargin: Float,
        recordingBottomMargin: Float,
        soundpadBottomMargin: Float,
    ): View {
        val blue = ColorUtils.blendARGB(callColor, 0xff2389e8.toInt(), 0.62f)
        val green = ColorUtils.blendARGB(callColor2, 0xff30c690.toInt(), 0.62f)
        val recording = if (InuConfig.CALL_RECORDING.value) {
            CallRecordingHelper.attach(root, peerName, blue, green, recordingBottomMargin)
        } else null
        val soundpad = if (InuConfig.CALL_SOUNDPAD.value) {
            CallSoundpadHelper.attach(root, blue, green, soundpadBottomMargin)
        } else null
        val launcher = ToolsButton(root.context, blue, green)
        root.addView(
            launcher,
            FrameLayout.LayoutParams(AndroidUtilities.dp(156f), AndroidUtilities.dp(50f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = AndroidUtilities.dp(launcherBottomMargin)
            },
        )
        val holder = Holder(launcher, recording, soundpad)
        holderRef = WeakReference(holder)
        launcher.setOnClickListener {
            launcher.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            holder.setExpanded(!holder.expanded, true)
        }
        holder.setExpanded(false, false)
        return launcher
    }

    @JvmStatic
    fun detach(view: View?) {
        val holder = holderRef?.get()
        if (holder == null || holder.launcher !== view) return
        CallRecordingHelper.detach(holder.recording)
        CallSoundpadHelper.detach(holder.soundpad)
        holderRef?.clear()
        holderRef = null
    }

    @JvmStatic
    fun onCallStateChanged(state: Int) {
        if (InuConfig.CALL_RECORDING.value) CallRecordingHelper.onCallStateChanged(state)
        if (InuConfig.CALL_SOUNDPAD.value) CallSoundpadHelper.onCallStateChanged(state)
        val active = state == VoIPService.STATE_ESTABLISHED || state == VoIPService.STATE_RECONNECTING
        holderRef?.get()?.setCallActive(active)
    }

    private class Holder(
        val launcher: ToolsButton,
        val recording: View?,
        val soundpad: View?,
    ) {
        var expanded = false

        fun setCallActive(active: Boolean) {
            launcher.setCallActive(active)
            if (!active) setExpanded(false, true)
            else setExpanded(expanded, false)
        }

        fun setExpanded(value: Boolean, animated: Boolean) {
            expanded = value
            launcher.setExpanded(value)
            reveal(recording, value, AndroidUtilities.dp(62f).toFloat(), animated)
            reveal(soundpad, value, AndroidUtilities.dp(128f).toFloat(), animated)
        }

        private fun reveal(view: View?, show: Boolean, collapsedTranslation: Float, animated: Boolean) {
            view ?: return
            view.animate().cancel()
            view.isClickable = show
            if (show) view.visibility = View.VISIBLE
            val targetAlpha = if (show) 1f else 0f
            val targetScale = if (show) 1f else 0.76f
            val targetTranslation = if (show) 0f else collapsedTranslation
            if (!animated) {
                view.alpha = targetAlpha
                view.scaleX = targetScale
                view.scaleY = targetScale
                view.translationY = targetTranslation
                if (!show) view.visibility = View.INVISIBLE
                return
            }
            view.animate()
                .alpha(targetAlpha)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationY(targetTranslation)
                .setDuration(if (show) 300 else 220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction { if (!show) view.visibility = View.INVISIBLE }
                .start()
        }
    }

    private class ToolsButton(context: Context, color1: Int, color2: Int) : View(context) {
        private val bounds = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = AndroidUtilities.dp(14f).toFloat()
            typeface = AndroidUtilities.bold()
        }
        private val gradient = LinearGradient(
            0f, 0f, AndroidUtilities.dp(156f).toFloat(), 0f,
            color1, color2, Shader.TileMode.CLAMP,
        )
        private var expandedProgress = 0f
        private var active = false
        private var animator: ValueAnimator? = null

        init {
            isClickable = true
            isFocusable = true
            visibility = INVISIBLE
            alpha = 0f
            contentDescription = LocaleController.getString(R.string.InuCallToolsOpen)
        }

        fun setCallActive(value: Boolean) {
            active = value
            animate().cancel()
            if (value) {
                visibility = VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            } else {
                animate().alpha(0f).scaleX(0.84f).scaleY(0.84f).setDuration(180)
                    .withEndAction { if (!active) visibility = INVISIBLE }.start()
            }
        }

        fun setExpanded(value: Boolean) {
            contentDescription = LocaleController.getString(
                if (value) R.string.InuCallToolsClose else R.string.InuCallToolsOpen,
            )
            animator?.cancel()
            animator = ValueAnimator.ofFloat(expandedProgress, if (value) 1f else 0f).apply {
                duration = 260
                interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
                addUpdateListener {
                    expandedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.shader = gradient
            paint.alpha = 238
            canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = AndroidUtilities.dp(1f).toFloat()
            paint.color = 0x55ffffff
            canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint)
            paint.style = Paint.Style.FILL

            val cx = AndroidUtilities.dp(24f).toFloat()
            val cy = height / 2f
            paint.color = Color.WHITE
            paint.strokeWidth = AndroidUtilities.dp(2f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - AndroidUtilities.dp(6f), cy, cx + AndroidUtilities.dp(6f), cy, paint)
            val vertical = 1f - expandedProgress
            canvas.drawLine(cx, cy - AndroidUtilities.dp(6f) * vertical, cx, cy + AndroidUtilities.dp(6f) * vertical, paint)

            val text = LocaleController.getString(R.string.InuCallTools)
            val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(text, AndroidUtilities.dp(43f).toFloat(), baseline, textPaint)
        }
    }
}
