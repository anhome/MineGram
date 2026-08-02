package desu.mintgram.helpers

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import java.util.WeakHashMap

/** Small in-app keyboard for the short Galaxy Z Flip cover screen. */
object FlipFloatingKeyboardHelper {
    private val attached = WeakHashMap<EditText, Unit>()
    private var popup: PopupWindow? = null
    private var target: EditText? = null
    private var russian = false
    private var uppercase = false

    @JvmStatic
    fun attach(editText: EditText) {
        if (attached.put(editText, Unit) != null) return
        editText.setOnTouchListener { view, event ->
            if (!InuConfig.FLIP_FLOATING_KEYBOARD.value ||
                !FlipDeviceHelper.isCompactWindow(view.context)
            ) {
                editText.showSoftInputOnFocus = true
                return@setOnTouchListener false
            }
            editText.showSoftInputOnFocus = false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    editText.requestFocus()
                    val offset = editText.getOffsetForPosition(event.x, event.y)
                    if (offset >= 0) editText.setSelection(offset.coerceAtMost(editText.length()))
                    AndroidUtilities.hideKeyboard(editText)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    editText.performClick()
                    show(editText)
                    true
                }
                else -> true
            }
        }
    }

    private fun show(editText: EditText, resetLayout: Boolean = true) {
        if (popup?.isShowing == true && target === editText) return
        popup?.dismiss()
        target = editText
        if (resetLayout) {
            russian = LocaleController.getInstance().currentLocale?.language == "ru"
            uppercase = false
        }

        val content = createKeyboard(editText)
        val screenWidth = editText.resources.displayMetrics.widthPixels
        val width = minOf(screenWidth - AndroidUtilities.dp(24f), AndroidUtilities.dp(340f))
        popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            elevation = AndroidUtilities.dp(8f).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setOnDismissListener {
                if (target === editText) target = null
            }
            showAtLocation(editText.rootView, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, AndroidUtilities.dp(62f))
        }
    }

    private fun createKeyboard(editText: EditText): View {
        val context = editText.context
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(7f), AndroidUtilities.dp(6f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(18f).toFloat()
                setColor(Theme.getColor(Theme.key_chat_messagePanelBackground))
                setStroke(AndroidUtilities.dp(1f), Theme.getColor(Theme.key_divider))
            }
        }

        val rows = if (russian) {
            listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        } else {
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }
        rows.forEach { letters ->
            val row = keyRow(context)
            letters.forEach { letter -> row.addView(key(context, letter.toString(), 1f) { insert(letter.toString()) }) }
            panel.addView(row)
        }

        val actions = keyRow(context)
        actions.addView(key(context, if (russian) "RU" else "EN", 1.15f) {
            russian = !russian
            popup?.dismiss()
            show(editText, false)
        })
        actions.addView(key(context, "⇧", 1f) {
            uppercase = !uppercase
            popup?.dismiss()
            show(editText, false)
        })
        actions.addView(key(context, if (russian) "Пробел" else "Space", 3.5f) { insert(" ") })
        actions.addView(key(context, "⌫", 1.15f) { backspace() })
        actions.addView(key(context, "↵", 1f) { insert("\n") })
        panel.addView(actions)
        return panel
    }

    private fun keyRow(context: android.content.Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun key(context: android.content.Context, label: String, weight: Float, action: () -> Unit): View =
        TextView(context).apply {
            text = if (uppercase && label.length == 1) label.uppercase() else label
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_chat_messagePanelText))
            textSize = 15f
            minHeight = AndroidUtilities.dp(38f)
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_listSelector),
            )
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, AndroidUtilities.dp(40f), weight).apply {
                setMargins(AndroidUtilities.dp(2f), AndroidUtilities.dp(2f), AndroidUtilities.dp(2f), AndroidUtilities.dp(2f))
            }
        }

    private fun insert(value: String) {
        val editText = target ?: return
        val editable = editText.text ?: return
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        val inserted = if (uppercase) value.uppercase() else value
        editable.replace(start, end, inserted)
    }

    private fun backspace() {
        val editText = target ?: return
        val editable: Editable = editText.text ?: return
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        when {
            end > start -> editable.delete(start, end)
            start > 0 -> {
                val previous = Character.offsetByCodePoints(editable, start, -1)
                editable.delete(previous, start)
            }
        }
    }
}
