package desu.mintgram.ui.settings

import android.text.InputType
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.helpers.security.CameraLockHelper
import desu.mintgram.helpers.security.TimedCodeLock
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class CameraLockSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCameraLock)

    private var timeoutSlider: SliderCell? = null

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuCameraLockEnable,
                R.string.InuCameraLockEnableInfo,
                { CameraLockHelper.isEnabled() }
            )
        )
        if (!CameraLockHelper.isEnabled()) return
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCameraLockMethod)))
        items.add(
            UItem.asCheck(RADIO_CODE, LocaleController.getString(R.string.InuCameraLockMethodCode))
                .setChecked(CameraLockHelper.getMethod() == TimedCodeLock.Method.CODE)
        )
        items.add(
            UItem.asCheck(RADIO_BIOMETRIC, LocaleController.getString(R.string.InuCameraLockMethodBiometric))
                .setChecked(CameraLockHelper.getMethod() == TimedCodeLock.Method.BIOMETRIC)
        )
        items.add(UItem.asShadow(if (!BiometricHelper.isSupported()) LocaleController.getString(R.string.InuCameraLockBiometricUnsupported) else null))

        if (CameraLockHelper.getMethod() == TimedCodeLock.Method.CODE) {
            items.add(
                UItem.asButton(
                    BUTTON_SET_CODE,
                    LocaleController.getString(
                        if (CameraLockHelper.hasCode()) R.string.InuCameraLockChangeCode else R.string.InuCameraLockSetCode
                    )
                )
            )
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLockFrequency)))
        items.add(
            UItem.asCheck(RADIO_EVERY_TIME, LocaleController.getString(R.string.InuLockFrequencyEveryTime))
                .setChecked(CameraLockHelper.getFrequency() == TimedCodeLock.Frequency.EVERY_TIME)
        )
        items.add(
            UItem.asCheck(RADIO_TIMEOUT, LocaleController.getString(R.string.InuLockFrequencyTimeout))
                .setChecked(CameraLockHelper.getFrequency() == TimedCodeLock.Frequency.TIMEOUT)
        )
        if (CameraLockHelper.getFrequency() == TimedCodeLock.Frequency.TIMEOUT) {
            if (timeoutSlider == null) timeoutSlider = SliderCell(
                context,
                min = 1f,
                max = 60f,
                defaultValue = 5f,
                initialValue = CameraLockHelper.getTimeoutMinutes().toFloat(),
                step = 1f,
                format = { LocaleController.formatString(R.string.InuLockFrequencyMinutesFormat, it.toInt()) },
                onChanged = { CameraLockHelper.setTimeoutMinutes(it.toInt()) },
            )
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuLockFrequencyTimeoutMinutes)))
            items.add(UItem.asCustom(timeoutSlider))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val enabling = !CameraLockHelper.isEnabled()
                if (enabling && CameraLockHelper.getMethod() == TimedCodeLock.Method.CODE && !CameraLockHelper.hasCode()) {
                    promptSetCode {
                        CameraLockHelper.setEnabled(true)
                        listView?.adapter?.update(true)
                    }
                    return
                }
                CameraLockHelper.setEnabled(enabling)
                (view as? TextCheckCell)?.isChecked = enabling
                listView?.adapter?.update(true)
            }

            RADIO_CODE -> {
                CameraLockHelper.setMethod(TimedCodeLock.Method.CODE)
                listView?.adapter?.update(true)
            }

            RADIO_BIOMETRIC -> {
                CameraLockHelper.setMethod(TimedCodeLock.Method.BIOMETRIC)
                listView?.adapter?.update(true)
            }

            RADIO_EVERY_TIME -> {
                CameraLockHelper.setFrequency(TimedCodeLock.Frequency.EVERY_TIME)
                listView?.adapter?.update(true)
            }

            RADIO_TIMEOUT -> {
                CameraLockHelper.setFrequency(TimedCodeLock.Frequency.TIMEOUT)
                listView?.adapter?.update(true)
            }

            BUTTON_SET_CODE -> promptSetCode { listView?.adapter?.update(true) }
        }
    }

    private fun promptSetCode(onSet: () -> Unit) {
        showInputDialog(
            this,
            title = LocaleController.getString(R.string.InuCameraLockSetCode),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onSubmit = { code ->
                if (code.isEmpty()) {
                    false
                } else {
                    CameraLockHelper.setCode(code)
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
        private val RADIO_EVERY_TIME = InuUtils.generateId()
        private val RADIO_TIMEOUT = InuUtils.generateId()
        private val BUTTON_SET_CODE = InuUtils.generateId()
    }
}
