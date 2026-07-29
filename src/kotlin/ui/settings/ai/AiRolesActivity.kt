package desu.mintgram.ui.settings.ai

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.ai.AiConfigHelper
import desu.mintgram.helpers.ai.AiRole
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiRolesActivity : SettingsPageActivity() {
    private var customRoles = AiConfigHelper.loadCustomRoles().toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiRoles)

    private fun allRoles(): List<AiRole> = AiConfigHelper.builtinRoles() + customRoles

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiRolesInfo)))

        val selectedName = AiConfigHelper.selectedRole().name
        for ((i, r) in allRoles().withIndex()) {
            val title = if (r.name == selectedName) "✓ ${r.name}" else r.name
            items.add(UItem.asButton(ITEM_BASE + i, title, r.prompt))
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuAiRoleAdd)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ADD -> showEditDialog(-1)
            else -> {
                val idx = item.id - ITEM_BASE
                val all = allRoles()
                if (idx in all.indices) {
                    AiConfigHelper.selectRole(all[idx])
                    listView.adapter.update(true)
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val idx = item.id - ITEM_BASE
        val all = allRoles()
        if (idx !in all.indices || all[idx].builtin) return false
        val customIdx = idx - AiConfigHelper.builtinRoles().size
        ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(listView.getClipBackground(view))
            .add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit)) { showEditDialog(customIdx) }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
                customRoles.removeAt(customIdx)
                AiConfigHelper.saveCustomRoles(customRoles)
                listView.adapter.update(true)
            }
            .show()
        return true
    }

    private fun showEditDialog(index: Int) {
        val ctx = context ?: return
        val existing = if (index >= 0) customRoles[index] else null

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val nameInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuAiRoleName)
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val promptInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuAiRolePrompt)
            setText(existing?.prompt ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            textSize = 16f
        }
        container.addView(promptInput, LinearLayout.LayoutParams(-1, -2))

        val title = if (existing != null)
            LocaleController.getString(R.string.InuAiRoleEdit)
        else
            LocaleController.getString(R.string.InuAiRoleAdd)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (name.isEmpty() || prompt.isEmpty()) return@setPositiveButton
                if (AiConfigHelper.builtinRoles().any { it.name == name }) return@setPositiveButton
                val role = AiRole(name, prompt, builtin = false)
                if (index >= 0) customRoles[index] = role else customRoles.add(role)
                AiConfigHelper.saveCustomRoles(customRoles)
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        showDialog(builder.create())
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ITEM_BASE = 21_000
    }
}
