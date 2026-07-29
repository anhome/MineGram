package desu.mintgram.ui.settings

import android.text.InputType
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.helpers.security.SettingsLockHelper
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class SettingsLockSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettingsLock)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuSettingsLockEnable,
                R.string.InuSettingsLockEnableInfo,
                { SettingsLockHelper.isEnabled() }
            )
        )
        if (!SettingsLockHelper.isEnabled()) return
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSettingsLockMethod)))
        items.add(
            UItem.asCheck(RADIO_CODE, LocaleController.getString(R.string.InuSettingsLockMethodCode))
                .setChecked(SettingsLockHelper.getMethod() == SettingsLockHelper.Method.CODE)
        )
        items.add(
            UItem.asCheck(RADIO_BIOMETRIC, LocaleController.getString(R.string.InuSettingsLockMethodBiometric))
                .setChecked(SettingsLockHelper.getMethod() == SettingsLockHelper.Method.BIOMETRIC)
        )
        items.add(UItem.asShadow(if (!BiometricHelper.isSupported()) LocaleController.getString(R.string.InuSettingsLockBiometricUnsupported) else null))

        if (SettingsLockHelper.getMethod() == SettingsLockHelper.Method.CODE) {
            items.add(
                UItem.asButton(
                    BUTTON_SET_CODE,
                    LocaleController.getString(
                        if (SettingsLockHelper.hasCode()) R.string.InuSettingsLockChangeCode else R.string.InuSettingsLockSetCode
                    )
                )
            )
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val enabling = !SettingsLockHelper.isEnabled()
                if (enabling && SettingsLockHelper.getMethod() == SettingsLockHelper.Method.CODE && !SettingsLockHelper.hasCode()) {
                    promptSetCode {
                        SettingsLockHelper.setEnabled(true)
                        listView?.adapter?.update(true)
                    }
                    return
                }
                SettingsLockHelper.setEnabled(enabling)
                (view as? TextCheckCell)?.isChecked = enabling
                listView?.adapter?.update(true)
            }

            RADIO_CODE -> {
                SettingsLockHelper.setMethod(SettingsLockHelper.Method.CODE)
                listView?.adapter?.update(true)
            }

            RADIO_BIOMETRIC -> {
                SettingsLockHelper.setMethod(SettingsLockHelper.Method.BIOMETRIC)
                listView?.adapter?.update(true)
            }

            BUTTON_SET_CODE -> promptSetCode { listView?.adapter?.update(true) }
        }
    }

    private fun promptSetCode(onSet: () -> Unit) {
        showInputDialog(
            this,
            title = LocaleController.getString(R.string.InuSettingsLockSetCode),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onSubmit = { code ->
                if (code.isEmpty()) {
                    false
                } else {
                    SettingsLockHelper.setCode(code)
                    onSet()
                    true
                }
            }
        )
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val RADIO_CODE = InuUtils.generateId()
        private val RADIO_BIOMETRIC = InuUtils.generateId()
        private val BUTTON_SET_CODE = InuUtils.generateId()
    }
}
