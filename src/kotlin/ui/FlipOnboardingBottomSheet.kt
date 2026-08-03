package desu.mintgram.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.mintgram.helpers.FlipDeviceHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/** Pink/purple Flip welcome sheet matching the compact cover-screen design. */
class FlipOnboardingBottomSheet(context: Context) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val compact = FlipDeviceHelper.isCompactWindow(context)
        val horizontalPadding = if (compact) 18 else 28
        val white = 0xffffffff.toInt()
        val softWhite = 0xfffbeaff.toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(horizontalPadding.toFloat()), AndroidUtilities.dp(18f), AndroidUtilities.dp(horizontalPadding.toFloat()), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xffc95bd2.toInt(), 0xffe76ac0.toInt(), 0xffb74ed0.toInt()),
            ).apply { cornerRadius = AndroidUtilities.dp(28f).toFloat() }
        }

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            text = "MintGram"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 34f else 42f)
            typeface = AndroidUtilities.bold()
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0, 0))

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            text = "──────  ♧  ──────"
            setTextColor(softWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28, 0, 0, 0, 0, 2))

        container.addView(ImageView(context).apply {
            setImageResource(R.mipmap.icon_background_mint)
            alpha = 0.92f
        }, LayoutHelper.createLinear(if (compact) 52 else 64, if (compact) 52 else 64, Gravity.CENTER_HORIZONTAL, 0, if (compact) 14 else 24, 0, 0))

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 19f else 22f)
            typeface = AndroidUtilities.bold()
            text = "Интерфейс MintGram идеально\nадаптирован для маленьких экранов"
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, horizontalPadding, horizontalPadding, 0))

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(softWhite)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 13f else 15f)
            text = "раскладушек ${FlipDeviceHelper.profileName()}"
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 7, horizontalPadding, horizontalPadding, if (compact) 8 else 14))

        fun feature(iconRes: Int, titleRes: Int, descriptionRes: Int) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ImageView(context).apply {
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(
                    white,
                    PorterDuff.Mode.SRC_IN,
                )
            }, LayoutHelper.createLinear(24, 24, Gravity.TOP, 0, 2, 12, 0))

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                setTextColor(white)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(titleRes)
            })
            texts.addView(TextView(context).apply {
                setTextColor(softWhite)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                text = LocaleController.getString(descriptionRes)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, horizontalPadding, if (compact) 9 else 12, horizontalPadding, if (compact) 9 else 12))
        }

        feature(R.drawable.msg_customize, R.string.InuFlipFeatureAdaptiveTitle, R.string.InuFlipFeatureAdaptiveDesc)
        feature(R.drawable.msg_list, R.string.InuFlipFeatureDrawerTitle, R.string.InuFlipFeatureDrawerDesc)
        feature(R.drawable.input_keyboard, R.string.InuFlipFeatureKeyboardTitle, R.string.InuFlipFeatureKeyboardDesc)
        feature(R.drawable.msg_expand, R.string.InuFlipFeatureContinuityTitle, R.string.InuFlipFeatureContinuityDesc)

        container.addView(TextView(context).apply {
            text = LocaleController.getString(R.string.InuFlipOnboardingContinue)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            typeface = AndroidUtilities.bold()
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(21f).toFloat()
                setColor(0xff9c39bd.toInt())
            }
            setOnClickListener { dismiss() }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, horizontalPadding, if (compact) 8 else 12, horizontalPadding, if (compact) 12 else 18))

        setCustomView(NestedScrollView(context).apply {
            isFillViewport = true
            addView(container)
        })
    }
}
