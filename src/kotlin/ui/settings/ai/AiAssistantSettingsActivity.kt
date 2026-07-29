package desu.mintgram.ui.settings.ai

import android.view.View
import desu.mintgram.InuConfig
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.ai.AiConfigHelper
import desu.mintgram.ui.settings.SettingsPageActivity
import desu.mintgram.ui.settings.SliderCell
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiAssistantSettingsActivity : SettingsPageActivity() {
    private var temperatureSlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiAssistant)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuAiAssistantEnable,
                R.string.InuAiAssistantEnableInfo,
                { InuConfig.AI_ENABLED.value }
            )
        )
        if (!InuConfig.AI_ENABLED.value) return
        items.add(UItem.asShadow(null))

        items.add(mkSubPageButton(BUTTON_SERVICES, LocaleController.getString(R.string.InuAiServices)))
        items.add(mkSubPageButton(BUTTON_ROLES, LocaleController.getString(R.string.InuAiRoles)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTemperature)))
        val slider = temperatureSlider ?: SliderCell(
            context,
            min = 0f,
            max = 20f,
            defaultValue = 10f,
            initialValue = InuConfig.AI_TEMPERATURE.value.toFloat(),
            step = 1f,
            format = { String.format(java.util.Locale.US, "%.1f", it / 10f) },
            onChanged = { InuConfig.AI_TEMPERATURE.value = it.toInt() },
        ).also { temperatureSlider = it }
        items.add(UItem.asCustom(slider))
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asRippleCheck(TOGGLE_STREAMING, LocaleController.getString(R.string.InuAiResponseStreaming))
                .setChecked(InuConfig.AI_RESPONSE_STREAMING.value)
        )
        items.add(
            UItem.asRippleCheck(TOGGLE_SAVE_HISTORY, LocaleController.getString(R.string.InuAiSaveHistory))
                .setChecked(InuConfig.AI_SAVE_HISTORY.value)
        )
        items.add(
            UItem.asRippleCheck(TOGGLE_SHOW_RESPONSE_ONLY, LocaleController.getString(R.string.InuAiShowResponseOnly))
                .setChecked(InuConfig.AI_SHOW_RESPONSE_ONLY.value)
        )
        items.add(
            UItem.asRippleCheck(TOGGLE_INSERT_AS_QUOTE, LocaleController.getString(R.string.InuAiInsertAsQuote))
                .setChecked(InuConfig.AI_INSERT_AS_QUOTE.value)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_CLEAR_HISTORY, R.drawable.msg_clear, LocaleController.getString(R.string.InuAiClearHistory)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val new = InuConfig.AI_ENABLED.toggle()
                (view as? TextCheckCell)?.setChecked(new)
                (view as? TextCheckCell)?.setBackgroundColorAnimated(
                    new,
                    Theme.getColor(if (new) Theme.key_windowBackgroundChecked else Theme.key_windowBackgroundUnchecked)
                )
                listView?.adapter?.update(true)
            }

            BUTTON_SERVICES -> presentFragment(AiServicesActivity())
            BUTTON_ROLES -> presentFragment(AiRolesActivity())

            TOGGLE_STREAMING -> {
                val new = InuConfig.AI_RESPONSE_STREAMING.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SAVE_HISTORY -> {
                val new = InuConfig.AI_SAVE_HISTORY.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_RESPONSE_ONLY -> {
                val new = InuConfig.AI_SHOW_RESPONSE_ONLY.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_INSERT_AS_QUOTE -> {
                val new = InuConfig.AI_INSERT_AS_QUOTE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_CLEAR_HISTORY -> confirmClearHistory()
        }
    }

    private fun confirmClearHistory() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuAiClearHistory))
            .setMessage(LocaleController.getString(R.string.InuAiClearHistoryConfirm))
            .setPositiveButton(LocaleController.getString(R.string.ClearButton)) { _, _ ->
                AiConfigHelper.clearHistory()
                BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.HistoryCleared)).show()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_SERVICES = InuUtils.generateId()
        private val BUTTON_ROLES = InuUtils.generateId()
        private val TOGGLE_STREAMING = InuUtils.generateId()
        private val TOGGLE_SAVE_HISTORY = InuUtils.generateId()
        private val TOGGLE_SHOW_RESPONSE_ONLY = InuUtils.generateId()
        private val TOGGLE_INSERT_AS_QUOTE = InuUtils.generateId()
        private val BUTTON_CLEAR_HISTORY = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-assistant",
            titleRes = R.string.InuAiAssistant,
            iconRes = R.drawable.magic_stick_solar,
            factory = ::AiAssistantSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-services", R.string.InuAiServices, BUTTON_SERVICES),
                SearchRegistry.Entry("ai-roles", R.string.InuAiRoles, BUTTON_ROLES),
            ),
        )
    }
}
