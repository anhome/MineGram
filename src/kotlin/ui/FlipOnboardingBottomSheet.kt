package desu.mintgram.ui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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

/** A compact, device-specific first-run sheet for Galaxy Z Flip and its FlexWindow. */
class FlipOnboardingBottomSheet(context: Context) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val compact = FlipDeviceHelper.isCompactWindow(context)
        val horizontalPadding = if (compact) 18 else 28
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        container.addView(ImageView(context).apply {
            setImageResource(R.mipmap.icon_background_mint)
        }, LayoutHelper.createLinear(if (compact) 52 else 64, if (compact) 52 else 64, Gravity.CENTER_HORIZONTAL, 0, if (compact) 14 else 24, 0, 0))

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 18f else 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuFlipOnboardingTitle)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, horizontalPadding, horizontalPadding, 0))

        container.addView(TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 13f else 14f)
            text = LocaleController.getString(
                if (FlipDeviceHelper.isGalaxyZFlip8) R.string.InuFlip8OnboardingSubtitle
                else R.string.InuFlipOnboardingSubtitle,
            )
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 7, horizontalPadding, horizontalPadding, if (compact) 8 else 14))

        fun feature(iconRes: Int, titleRes: Int, descriptionRes: Int) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ImageView(context).apply {
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon),
                    PorterDuff.Mode.SRC_IN,
                )
            }, LayoutHelper.createLinear(24, 24, Gravity.TOP, 0, 2, 12, 0))

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(titleRes)
            })
            texts.addView(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
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
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f)
            setOnClickListener { dismiss() }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, horizontalPadding, if (compact) 8 else 12, horizontalPadding, if (compact) 12 else 18))

        setCustomView(NestedScrollView(context).apply {
            isFillViewport = true
            addView(container)
        })
    }
}
