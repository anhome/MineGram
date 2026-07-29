package desu.mintgram.ui.pillstack

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.ui.ActionBar.Theme

/** Gradient rounded-rect background for [RatePill]s — flat color per pill (BTC orange, USD green, ...). */
class ColoredBackground(topColor: Int = -14965523, bottomColor: Int = -15431455) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, dp(28f).toFloat(), intArrayOf(topColor, bottomColor), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
        shader = LinearGradient(0f, 0f, 0f, dp(28f).toFloat(), intArrayOf(1308622847, 0, 452984831), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val rectF = RectF()

    override fun getOpacity() = -2

    override fun draw(canvas: Canvas) {
        val radius = dp(14f).toFloat()
        rectF.set(bounds)
        canvas.drawRoundRect(rectF, radius, radius, paint)
        if (!Theme.isCurrentThemeDark()) return
        val strokeWidth = dp(1f).toFloat()
        strokePaint.strokeWidth = strokeWidth
        rectF.inset(strokeWidth / 2f, strokeWidth / 2f)
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }
}
