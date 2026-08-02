package desu.mintgram.helpers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Branding that is deliberately scoped to the verified MintGram announcement channel. */
object OfficialChannelHelper {
    private const val CHANNEL_USERNAME = "MintGramTG"
    private const val SUPPORT_ANIMATION_MS = 5_000L
    private const val SUPPORT_OVERLAY_TAG = "mintgram_official_support_overlay"

    @JvmStatic
    fun isOfficialChannel(chat: TLRPC.Chat?): Boolean =
        chat != null && CHANNEL_USERNAME.equals(chat.username, ignoreCase = true)

    @JvmStatic
    fun showOfficialChannelBulletin(fragment: BaseFragment) {
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuOfficialChannelInfo))
            .show()
    }

    @JvmStatic
    fun onChatOpened(activity: ChatActivity) {
        if (!isOfficialChannel(activity.currentChat)) return
        val context = activity.context ?: return
        if (activity.parentActivity?.isFinishing == true) return
        activity.showDialog(
            AlertDialog.Builder(context, activity.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuOfficialChannelTitle))
                .setMessage(LocaleController.getString(R.string.InuOfficialChannelDialog))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create(),
        )
    }

    /** Runs only after Telegram confirms a paid reaction on MintGramTG. */
    @JvmStatic
    fun onStarsSent(activity: ChatActivity?) {
        if (activity == null || !isOfficialChannel(activity.currentChat)) return
        val root = activity.fragmentView as? ViewGroup ?: return
        val context = root.context

        root.findViewWithTag<View>(SUPPORT_OVERLAY_TAG)?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }

        val overlay = FrameLayout(context).apply {
            tag = SUPPORT_OVERLAY_TAG
            isClickable = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val title = TextView(context).apply {
            text = LocaleController.getString(R.string.InuStarsSupportThanks)
            gravity = Gravity.CENTER
            textSize = 19f
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, activity.resourceProvider))
            setShadowLayer(AndroidUtilities.dp(10f).toFloat(), 0f, AndroidUtilities.dp(2f).toFloat(), 0x66000000)
            alpha = 0f
            translationY = -AndroidUtilities.dp(18f).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        overlay.addView(
            title,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                24f,
                78f,
                24f,
                0f,
            ),
        )

        val star = JellyStarView(context).apply {
            alpha = 0f
            scaleX = 0.55f
            scaleY = 0.55f
        }
        overlay.addView(star, LayoutHelper.createFrame(124, 124, Gravity.CENTER))

        overlay.post {
            title.announceForAccessibility(title.text)
            title.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(440)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .start()
            star.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(520)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                .start()

            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SUPPORT_ANIMATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    val wave = sin(progress * PI.toFloat() * 20f)
                    star.setRainbowProgress(progress)
                    star.translationX = wave * AndroidUtilities.dp(18f) * (1f - progress * 0.22f)
                    star.rotation = sin(progress * PI.toFloat() * 16f) * 8f
                    star.scaleX = 1f + wave * 0.16f
                    star.scaleY = 1f - wave * 0.10f
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        overlay.animate()
                            .alpha(0f)
                            .setDuration(260)
                            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                            .start()
                    }
                })
                start()
            }
        }
    }

    private class JellyStarView(context: Context) : View(context) {
        private val path = Path()
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            setShadowLayer(AndroidUtilities.dp(16f).toFloat(), 0f, 0f, Color.MAGENTA)
        }
        private var color = Color.MAGENTA

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setRainbowProgress(progress: Float) {
            color = Color.HSVToColor(floatArrayOf((progress * 720f) % 360f, 0.82f, 1f))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) * 0.36f
            val inner = outer * 0.44f

            haloPaint.color = color and 0x00FFFFFF or 0x28000000
            canvas.drawCircle(cx, cy, outer * 1.22f, haloPaint)

            path.reset()
            for (index in 0 until 10) {
                val radius = if (index % 2 == 0) outer else inner
                val angle = -PI / 2.0 + index * PI / 5.0
                val x = cx + cos(angle).toFloat() * radius
                val y = cy + sin(angle).toFloat() * radius
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            starPaint.color = color
            starPaint.setShadowLayer(AndroidUtilities.dp(16f).toFloat(), 0f, 0f, color)
            canvas.drawPath(path, starPaint)

            starPaint.clearShadowLayer()
            starPaint.color = 0xB3FFFFFF.toInt()
            canvas.drawCircle(cx - outer * 0.22f, cy - outer * 0.20f, outer * 0.10f, starPaint)
        }
    }
}
