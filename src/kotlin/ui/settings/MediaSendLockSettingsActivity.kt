package desu.mintgram.ui.settings

import android.text.InputType
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.helpers.security.MediaSendLockHelper
import desu.mintgram.helpers.security.TimedCodeLock
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MediaSendLockSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMediaSendLock)

    private var timeoutSlider: SliderCell? = null

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuMediaSendLockEnable,
                R.string.InuMediaSendLockEnableInfo,
                { MediaSendLockHelper.isEnabled() }
            )
        )
        if (!MediaSendLockHelper.isEnabled()) return
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMediaSendLockMethod)))
        items.add(
            UItem.asCheck(RADIO_CODE, LocaleController.getString(R.string.InuMediaSendLockMethodCode))
                .setChecked(MediaSendLockHelper.getMethod() == TimedCodeLock.Method.CODE)
        )
        items.add(
            UItem.asCheck(RADIO_BIOMETRIC, LocaleController.getString(R.string.InuMediaSendLockMethodBiometric))
                .setChecked(MediaSendLockHelper.getMethod() == TimedCodeLock.Method.BIOMETRIC)
        )
        items.add(UItem.asShadow(if (!BiometricHelper.isSupported()) LocaleController.getString(R.string.InuMediaSendLockBiometricUnsupported) else null))

        if (MediaSendLockHelper.getMethod() == TimedCodeLock.Method.CODE) {
            items.add(
                UItem.asButton(
                    BUTTON_SET_CODE,
                    LocaleController.getString(
                        if (MediaSendLockHelper.hasCode()) R.string.InuMediaSendLockChangeCode else R.string.InuMediaSendLockSetCode
                    )
                )
            )
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLockFrequency)))
        items.add(
            UItem.asCheck(RADIO_EVERY_TIME, LocaleController.getString(R.string.InuLockFrequencyEveryTime))
                .setChecked(MediaSendLockHelper.getFrequency() == TimedCodeLock.Frequency.EVERY_TIME)
        )
        items.add(
            UItem.asCheck(RADIO_TIMEOUT, LocaleController.getString(R.string.InuLockFrequencyTimeout))
                .setChecked(MediaSendLockHelper.getFrequency() == TimedCodeLock.Frequency.TIMEOUT)
        )
        if (MediaSendLockHelper.getFrequency() == TimedCodeLock.Frequency.TIMEOUT) {
            if (timeoutSlider == null) timeoutSlider = SliderCell(
                context,
                min = 1f,
                max = 60f,
                defaultValue = 5f,
                initialValue = MediaSendLockHelper.getTimeoutMinutes().toFloat(),
                step = 1f,
                format = { LocaleController.formatString(R.string.InuLockFrequencyMinutesFormat, it.toInt()) },
                onChanged = { MediaSendLockHelper.setTimeoutMinutes(it.toInt()) },
            )
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuLockFrequencyTimeoutMinutes)))
            items.add(UItem.asCustom(timeoutSlider))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                val enabling = !MediaSendLockHelper.isEnabled()
                if (enabling && MediaSendLockHelper.getMethod() == TimedCodeLock.Method.CODE && !MediaSendLockHelper.hasCode()) {
                    promptSetCode {
                        MediaSendLockHelper.setEnabled(true)
                        listView?.adapter?.update(true)
                    }
                    return
                }
                MediaSendLockHelper.setEnabled(enabling)
                (view as? TextCheckCell)?.isChecked = enabling
                listView?.adapter?.update(true)
            }

            RADIO_CODE -> {
                MediaSendLockHelper.setMethod(TimedCodeLock.Method.CODE)
                listView?.adapter?.update(true)
            }

            RADIO_BIOMETRIC -> {
                MediaSendLockHelper.setMethod(TimedCodeLock.Method.BIOMETRIC)
                listView?.adapter?.update(true)
            }

            RADIO_EVERY_TIME -> {
                MediaSendLockHelper.setFrequency(TimedCodeLock.Frequency.EVERY_TIME)
                listView?.adapter?.update(true)
            }

            RADIO_TIMEOUT -> {
                MediaSendLockHelper.setFrequency(TimedCodeLock.Frequency.TIMEOUT)
                listView?.adapter?.update(true)
            }

            BUTTON_SET_CODE -> promptSetCode { listView?.adapter?.update(true) }
        }
    }

    private fun promptSetCode(onSet: () -> Unit) {
        showInputDialog(
            this,
            title = LocaleController.getString(R.string.InuMediaSendLockSetCode),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onSubmit = { code ->
                if (code.isEmpty()) {
                    false
                } else {
                    MediaSendLockHelper.setCode(code)
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
