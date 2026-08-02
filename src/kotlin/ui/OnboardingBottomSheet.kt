package desu.mintgram.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper

class OnboardingBottomSheet(context: Context) : BottomSheet(context, false) {

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val entranceViews = ArrayList<View>()

        fun stage(view: View, distanceDp: Float = 12f) {
            view.alpha = 0f
            view.translationY = AndroidUtilities.dp(distanceDp).toFloat()
            entranceViews.add(view)
        }

        val icon = ImageView(context).apply {
            setImageResource(R.mipmap.icon_background_mint)
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
        }
        entranceViews.add(icon)
        container.addView(icon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0))

        val title = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuOnboardingTitle)
        }
        stage(title, 10f)
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 20, 20, 0))

        val subtitle = TextView(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setLineSpacing(lineSpacingExtra, lineSpacingMultiplier * 1.1f)
            text = LocaleController.getString(R.string.InuOnboardingSubtitle)
        }
        stage(subtitle, 8f)
        container.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 28, 28, 20))

        fun featureRow(iconRes: Int, titleRes: Int, descRes: Int) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            val iv = ImageView(context).apply {
                setImageResource(iconRes)
                colorFilter = android.graphics.PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon), android.graphics.PorterDuff.Mode.SRC_IN,
                )
            }
            row.addView(iv, LayoutHelper.createLinear(24, 24, Gravity.TOP, 0, 2, 14, 0))

            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(titleRes)
            })
            textCol.addView(TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setLineSpacing(lineSpacingExtra, lineSpacingMultiplier * 1.1f)
                text = LocaleController.getString(descRes)
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            stage(row, 10f)
            container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 14, 28, 14))
        }

        featureRow(R.drawable.msg_block, R.string.InuOnboardingFeatureAdsTitle, R.string.InuOnboardingFeatureAdsDesc)
        featureRow(R.drawable.ghost, R.string.InuOnboardingFeatureGhostTitle, R.string.InuOnboardingFeatureGhostDesc)
        featureRow(R.drawable.msg_message, R.string.InuOnboardingFeatureHistoryTitle, R.string.InuOnboardingFeatureHistoryDesc)
        featureRow(R.drawable.msg_secret, R.string.InuOnboardingFeatureArchiveLockTitle, R.string.InuOnboardingFeatureArchiveLockDesc)
        featureRow(R.drawable.msg_palette, R.string.InuOnboardingFeatureIconsTitle, R.string.InuOnboardingFeatureIconsDesc)

        val joinButton = TextView(context).apply {
            text = LocaleController.getString(R.string.InuOnboardingJoinChannel)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            typeface = AndroidUtilities.bold()
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 21f)
            setOnClickListener {
                isEnabled = false
                animate().scaleX(0.97f).scaleY(0.97f).alpha(0.75f).setDuration(90).withEndAction {
                    dismiss()
                    AndroidUtilities.runOnUIThread(
                        { Browser.openUrl(context, "https://t.me/MintGramTG") },
                        180,
                    )
                }.start()
            }
        }
        stage(joinButton, 8f)
        container.addView(joinButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 16, 16, 16, 8))

        val continueButton = TextView(context).apply {
            text = LocaleController.getString(R.string.InuOnboardingContinue)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(21f), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            )
            setOnClickListener { dismiss() }
        }
        stage(continueButton, 8f)
        container.addView(continueButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 16, 0, 16, 16))

        setCustomView(NestedScrollView(context).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container)
        })

        setOnShowListener {
            entranceViews.forEachIndexed { index, view ->
                view.animate().cancel()
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(45L * index)
                    .setDuration(420)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start()
            }
        }
    }
}
