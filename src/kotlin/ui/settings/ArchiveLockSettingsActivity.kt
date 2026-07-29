package desu.mintgram.ui.settings

import android.text.InputType
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.security.ArchiveLockHelper
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ArchiveLockSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuArchiveLock)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuArchiveLockEnable,
                R.string.InuArchiveLockEnableInfo,
                { ArchiveLockHelper.isEnabled() }
            )
        )
        if (!ArchiveLockHelper.isEnabled()) return
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuArchiveLockMethod)))
        items.add(
            UItem.asCheck(RADIO_CODE, LocaleController.getString(R.string.InuArchiveLockMethodCode))
                .setChecked(ArchiveLockHelper.getMethod() == ArchiveLockHelper.Method.CODE)
        )
        items.add(
            UItem.asCheck(RADIO_BIOMETRIC, LocaleController.getString(R.string.InuArchiveLockMethodBiometric))
                .setChecked(ArchiveLockHelper.getMethod() == ArchiveLockHelper.Method.BIOMETRIC)
        )
        items.add(UItem.asShadow(if (!BiometricHelper.isSupported()) LocaleController.getString(R.string.InuArchiveLockBiometricUnsupported) else null))

        if (ArchiveLockHelper.getMethod() == ArchiveLockHelper.Method.CODE) {
            items.add(
                UItem.asButton(
                    BUTTON_SET_CODE,
                    LocaleController.getString(
                        if (ArchiveLockHelper.hasCode()) R.string.InuArchiveLockChangeCode else R.string.InuArchiveLockSetCode
                    )
                )
            )
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val enabling = !ArchiveLockHelper.isEnabled()
                if (enabling && ArchiveLockHelper.getMethod() == ArchiveLockHelper.Method.CODE && !ArchiveLockHelper.hasCode()) {
                    promptSetCode {
                        ArchiveLockHelper.setEnabled(true)
                        listView?.adapter?.update(true)
                    }
                    return
                }
                ArchiveLockHelper.setEnabled(enabling)
                (view as? TextCheckCell)?.isChecked = enabling
                listView?.adapter?.update(true)
            }

            RADIO_CODE -> {
                ArchiveLockHelper.setMethod(ArchiveLockHelper.Method.CODE)
                listView?.adapter?.update(true)
            }

            RADIO_BIOMETRIC -> {
                ArchiveLockHelper.setMethod(ArchiveLockHelper.Method.BIOMETRIC)
                listView?.adapter?.update(true)
            }

            BUTTON_SET_CODE -> promptSetCode { listView?.adapter?.update(true) }
        }
    }

    private fun promptSetCode(onSet: () -> Unit) {
        showInputDialog(
            this,
            title = LocaleController.getString(R.string.InuArchiveLockSetCode),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onSubmit = { code ->
                if (code.isEmpty()) {
                    false
                } else {
                    ArchiveLockHelper.setCode(code)
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
