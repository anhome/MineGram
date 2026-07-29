package desu.mintgram.ui.ai

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.mintgram.helpers.ai.AiClient
import desu.mintgram.helpers.ai.AiConfigHelper
import desu.mintgram.helpers.ai.AiGenerationCallback
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

/** Shows a streaming AI response to a prompt, with Copy / Insert / Retry actions. */
class AiResponseBottomSheet private constructor(
    context: Context,
    private val fragment: BaseFragment,
    private var prompt: String,
    private val useHistory: Boolean,
    private val onInsert: ((String) -> Unit)?,
) : BottomSheet(context, false) {

    private var requestId: String? = null
    private var generating = false
    private var currentResponse = ""

    private val responseView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        setTextColor(getThemedColor(Theme.key_dialogTextBlack))
        setTextIsSelectable(true)
        setPadding(dp(22), dp(4), dp(22), dp(8))
    }

    private val mainButton = ButtonWithCounterView(context, resourcesProvider)
    private val copyButton: ImageView
    private val retryButton: ImageView
    private val insertButton: ImageView

    init {
        fixNavigationBar()

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val promptView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "→ $prompt"
        }
        headerRow.addView(promptView, LayoutHelper.createLinear(0, -2, 1f))

        fun iconButton(iconRes: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(getThemedColor(Theme.key_player_actionBarSubtitle))
            background = Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(18))
            visibility = View.GONE
            setOnClickListener { onClick() }
            headerRow.addView(this, LayoutHelper.createLinear(36, 36, 0f, 4f, 0f, 0f))
        }

        copyButton = iconButton(R.drawable.msg_copy) {
            if (AndroidUtilities.addToClipboard(currentResponse)) {
                BulletinFactory.of(containerView as FrameLayout, resourcesProvider).createCopyBulletin(
                    LocaleController.getString(R.string.TextCopied)
                ).show()
            }
        }
        retryButton = iconButton(R.drawable.msg_retry) { retry() }
        insertButton = iconButton(R.drawable.msg_send) {
            onInsert?.invoke(currentResponse)
            dismiss()
        }
        if (onInsert == null) insertButton.visibility = View.GONE

        container.addView(headerRow, LayoutHelper.createLinear(-1, -2, 0, 16f, 22f, 8f, 0f))
        container.addView(
            NestedScrollView(context).apply { addView(responseView) },
            LayoutHelper.createLinear(-1, -2)
        )

        mainButton.apply {
            setColor(getThemedColor(Theme.key_featuredStickers_addButton))
            setOnClickListener {
                if (generating) {
                    requestId?.let { AiClient.stopRequest(it) }
                    generating = false
                    updateButtons()
                } else {
                    dismiss()
                }
            }
        }
        container.addView(mainButton, LayoutHelper.createLinear(-1, 48, 0, 16, 16, 16, 16))

        setCustomView(container)
        generate()
    }

    private fun updateButtons() {
        mainButton.setText(
            LocaleController.getString(if (generating) R.string.Stop else R.string.Close),
            true
        )
        val done = !generating && currentResponse.isNotEmpty()
        copyButton.visibility = if (done) View.VISIBLE else View.GONE
        retryButton.visibility = if (done) View.VISIBLE else View.GONE
        insertButton.visibility = if (done && onInsert != null) View.VISIBLE else View.GONE
    }

    private fun retry() {
        AiConfigHelper.removeLastFromHistory()
        generate()
    }

    private fun generate() {
        generating = true
        currentResponse = ""
        responseView.text = LocaleController.getString(R.string.InuAiGenerating)
        updateButtons()
        requestId = AiClient.sendMessage(prompt, useHistory, object : AiGenerationCallback {
            override fun onChunk(text: String) {
                currentResponse = text
                responseView.text = text
            }

            override fun onResponse(text: String) {
                generating = false
                currentResponse = text
                responseView.text = text
                updateButtons()
            }

            override fun onError(code: Int, message: String) {
                generating = false
                updateButtons()
                BulletinFactory.of(fragment).createErrorBulletin(errorMessageFor(code, message)).show()
            }
        })
    }

    private fun errorMessageFor(code: Int, message: String): String = when {
        code == 401 || code == 403 -> LocaleController.getString(R.string.InuAiErrorUnauthorized)
        code == 429 -> LocaleController.getString(R.string.InuAiErrorRateLimited)
        code == 408 -> LocaleController.getString(R.string.InuAiErrorTimeout)
        code in 500..599 -> LocaleController.getString(R.string.InuAiErrorServer)
        else -> LocaleController.formatString(R.string.InuAiErrorGeneric, message)
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())

    companion object {
        fun show(fragment: BaseFragment, prompt: String, useHistory: Boolean, onInsert: ((String) -> Unit)?) {
            val ctx = fragment.parentActivity ?: return
            val sheet = AiResponseBottomSheet(ctx, fragment, prompt, useHistory, onInsert)
            fragment.showDialog(sheet)
        }
    }
}
