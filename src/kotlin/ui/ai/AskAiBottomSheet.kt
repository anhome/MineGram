package desu.mintgram.ui.ai

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.OutlineTextContainerView
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

/** Prompt-entry sheet, opened from the message context menu ("Ask AI"). */
class AskAiBottomSheet(
    context: Context,
    private val fragment: BaseFragment,
    initialPrompt: String,
    private val onInsert: ((String) -> Unit)?,
) : BottomSheet(context, false) {

    init {
        fixNavigationBar()
        smoothKeyboardAnimationEnabled = true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        val fieldContainer = OutlineTextContainerView(context, resourcesProvider).apply {
            setText(LocaleController.getString(R.string.InuAiPromptHint))
        }
        val promptField = EditTextBoldCursor(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
            background = null
            maxLines = 8
            if (initialPrompt.isNotEmpty()) setText(initialPrompt)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
            setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated))
            setCursorWidth(1.5f)
            setPadding(dp(16), 0, dp(16), 0)
            setCursorSize(dp(20))
        }
        fieldContainer.addView(promptField, LayoutHelper.createFrame(-1, -2f, 0, 0f, 16f, 0f, 16f))
        fieldContainer.attachEditText(promptField)
        container.addView(fieldContainer, LayoutHelper.createLinear(-1, -2))

        val useHistoryCell = CheckBoxCell(context, 1, resourcesProvider).apply {
            setText(LocaleController.getString(R.string.InuAiUseHistory), "", InuConfig.AI_SAVE_HISTORY.value, false)
            setOnClickListener { setChecked(!isChecked, true) }
        }
        container.addView(useHistoryCell, LayoutHelper.createLinear(-1, 40, 0f, 8f, 0f, 0f))

        val submitButton = ButtonWithCounterView(context, resourcesProvider).apply {
            setColor(getThemedColor(Theme.key_featuredStickers_addButton))
            setText(LocaleController.getString(R.string.InuAiAskQuestion), false)
            setOnClickListener {
                val prompt = promptField.text?.toString()?.trim().orEmpty()
                if (prompt.isEmpty()) {
                    AndroidUtilities.shakeViewSpring(fieldContainer)
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    return@setOnClickListener
                }
                dismiss()
                AiResponseBottomSheet.show(fragment, prompt, useHistoryCell.isChecked, onInsert)
            }
        }
        container.addView(submitButton, LayoutHelper.createLinear(-1, 48, 0, 0, 16, 0, 0))

        setCustomView(NestedScrollView(context).apply { addView(container) })
        setTitle(LocaleController.getString(R.string.InuAiAssistant), true)
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())
}
