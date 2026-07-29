package desu.mintgram.ui.settings.ai

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.ai.AiConfigHelper
import desu.mintgram.helpers.ai.AiService
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiServicesActivity : SettingsPageActivity() {
    private var services = AiConfigHelper.loadServices().toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiServices)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiServicesInfo)))

        val selectedId = AiConfigHelper.selectedService()?.id
        for ((i, s) in services.withIndex()) {
            val title = if (s.id == selectedId) "✓ ${s.model}" else s.model
            items.add(UItem.asButton(ITEM_BASE + i, title, s.url))
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuAiServiceAdd)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ADD -> showEditDialog(-1)
            else -> {
                val idx = item.id - ITEM_BASE
                if (idx in services.indices) {
                    AiConfigHelper.selectService(services[idx])
                    listView.adapter.update(true)
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val idx = item.id - ITEM_BASE
        if (idx !in services.indices) return false
        ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(listView.getClipBackground(view))
            .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit)) { showEditDialog(idx) }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
                services.removeAt(idx)
                AiConfigHelper.saveServices(services)
                listView.adapter.update(true)
            }
            .show()
        return true
    }

    private fun showEditDialog(index: Int) {
        val ctx = context ?: return
        val existing = if (index >= 0) services[index] else null

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        fun field(hintRes: Int, value: String?, multiline: Boolean = false, password: Boolean = false): EditText {
            val edit = EditText(ctx).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
                hint = LocaleController.getString(hintRes)
                setText(value ?: "")
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0) or
                    (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                isSingleLine = !multiline
                textSize = 16f
            }
            container.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return edit
        }

        val urlInput = field(R.string.InuAiServiceUrl, existing?.url)
        val modelInput = field(R.string.InuAiServiceModel, existing?.model)
        val keyInput = field(R.string.InuAiServiceKey, existing?.key, password = true)

        val reasoningCell = CheckBoxCell(ctx, 1).apply {
            setText(LocaleController.getString(R.string.InuAiServiceReasoning), "", existing?.reasoningEnabled == true, false)
            setPadding(dp(0), 0, dp(0), 0)
            setOnClickListener { setChecked(!isChecked, true) }
        }
        container.addView(reasoningCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40))

        val title = if (existing != null)
            LocaleController.getString(R.string.InuAiServiceEdit)
        else
            LocaleController.getString(R.string.InuAiServiceAdd)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val url = urlInput.text.toString().trim()
                val model = modelInput.text.toString().trim()
                val key = keyInput.text.toString().trim()
                if (url.isEmpty() || model.isEmpty()) return@setPositiveButton
                val service = AiService(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    url = url,
                    model = model,
                    key = key,
                    reasoningEnabled = reasoningCell.isChecked,
                )
                if (index >= 0) services[index] = service else services.add(service)
                AiConfigHelper.saveServices(services)
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        showDialog(builder.create())
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ITEM_BASE = 20_000
    }
}
