package desu.mintgram.ui.settings

import android.app.Activity
import android.content.Intent
import android.view.View
import desu.mintgram.InuConfig
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.calls.CallSoundpadHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class CallsSettingsActivity : SettingsPageActivity() {
    private var customSounds = emptyList<CallSoundpadHelper.CustomSound>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCallsSettings)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: parentActivity ?: return
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCallRecordingTitle)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_RECORDING,
                R.string.InuCallRecordingEnable,
                R.string.InuCallRecordingEnableInfo,
                { InuConfig.CALL_RECORDING.value },
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_RECORDINGS,
                R.drawable.msg_voice_headphones,
                LocaleController.getString(R.string.InuCallRecordingsMenu),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_FORMAT,
                LocaleController.getString(R.string.InuCallRecordingFormat),
                formatLabel(InuConfig.CALL_RECORDING_FORMAT.value),
            )
        )
        if (InuConfig.CALL_RECORDING_FORMAT.value != InuConfig.CallRecordingFormatItem.WAV) {
            items.add(
                UItem.asButton(
                    BUTTON_BITRATE,
                    LocaleController.getString(R.string.InuCallRecordingQuality),
                    LocaleController.formatString(
                        R.string.InuCallRecordingBitrateValue,
                        InuConfig.CALL_RECORDING_BITRATE.value,
                    ),
                )
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCallRecordingConsentInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCallSoundpadTitle)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SOUNDPAD,
                R.string.InuCallSoundpadEnable,
                R.string.InuCallSoundpadEnableInfo,
                { InuConfig.CALL_SOUNDPAD.value },
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ADD_SOUND,
                R.drawable.msg_add,
                LocaleController.getString(R.string.InuCallSoundpadAdd),
            )
        )
        customSounds = CallSoundpadHelper.getCustomSounds(ctx)
        customSounds.forEachIndexed { index, sound ->
            items.add(
                UItem.asButton(
                    CUSTOM_SOUND_BASE + index,
                    sound.displayName,
                    LocaleController.getString(R.string.InuCallSoundpadTapToRemove),
                )
            )
        }
        items.add(
            UItem.asShadow(
                if (customSounds.isEmpty()) LocaleController.getString(R.string.InuCallSoundpadCustomEmpty)
                else LocaleController.getString(R.string.InuCallSoundpadCustomInfo),
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_RECORDING -> {
                val checked = InuConfig.CALL_RECORDING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = checked
            }
            TOGGLE_SOUNDPAD -> {
                val checked = InuConfig.CALL_SOUNDPAD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = checked
            }
            BUTTON_RECORDINGS -> presentFragment(CallRecordingsActivity())
            BUTTON_FORMAT -> showFormatSelector()
            BUTTON_BITRATE -> showBitrateSelector()
            BUTTON_ADD_SOUND -> launchSoundPicker()
            else -> {
                val index = item.id - CUSTOM_SOUND_BASE
                if (index in customSounds.indices) confirmDelete(customSounds[index])
            }
        }
    }

    private fun launchSoundPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "audio/wav", "audio/flac"),
            )
        }
        runCatching { startActivityForResult(intent, REQUEST_SOUND) }
            .onFailure { BulletinFactory.of(this).createErrorBulletin(it.message ?: "").show() }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_SOUND || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        Utilities.globalQueue.postRunnable {
            val result = CallSoundpadHelper.importCustomSound(ctx, uri)
            AndroidUtilities.runOnUIThread {
                if (context == null) return@runOnUIThread
                if (result.isSuccess) {
                    listView.adapter.update(true)
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuCallSoundpadImported),
                    ).show()
                } else {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(R.string.InuCallSoundpadImportFailed),
                    ).show()
                }
            }
        }
    }

    private fun confirmDelete(sound: CallSoundpadHelper.CustomSound) {
        val ctx = context ?: return
        showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.InuCallSoundpadRemoveTitle))
                .setMessage(sound.displayName)
                .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                    CallSoundpadHelper.deleteCustomSound(sound)
                    listView.adapter.update(true)
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
    }

    private fun showFormatSelector() {
        val ctx = context ?: return
        val values = intArrayOf(
            InuConfig.CallRecordingFormatItem.OPUS,
            InuConfig.CallRecordingFormatItem.AAC,
            InuConfig.CallRecordingFormatItem.WAV,
        )
        val options = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCallRecordingFormatOpus), LocaleController.getString(R.string.InuCallRecordingFormatOpusInfo)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCallRecordingFormatAac), LocaleController.getString(R.string.InuCallRecordingFormatAacInfo)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCallRecordingFormatWav), LocaleController.getString(R.string.InuCallRecordingFormatWavInfo)),
        )
        showDialog(
            RadioDialogBuilder(ctx, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuCallRecordingFormat))
                .setItems(options, values.indexOf(InuConfig.CALL_RECORDING_FORMAT.value).coerceAtLeast(0)) { _, which ->
                    InuConfig.CALL_RECORDING_FORMAT.value = values[which]
                    listView.adapter.update(true)
                }
                .create()
        )
    }

    private fun showBitrateSelector() {
        val ctx = context ?: return
        val values = intArrayOf(64, 96)
        val options = values.map { RadioDialogBuilder.Item(LocaleController.formatString(R.string.InuCallRecordingBitrateValue, it)) }
        showDialog(
            RadioDialogBuilder(ctx, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuCallRecordingQuality))
                .setItems(options, values.indexOf(InuConfig.CALL_RECORDING_BITRATE.value).coerceAtLeast(0)) { _, which ->
                    InuConfig.CALL_RECORDING_BITRATE.value = values[which]
                    listView.adapter.update(true)
                }
                .create()
        )
    }

    private fun formatLabel(value: Int): String = LocaleController.getString(
        when (value) {
            InuConfig.CallRecordingFormatItem.AAC -> R.string.InuCallRecordingFormatAac
            InuConfig.CallRecordingFormatItem.WAV -> R.string.InuCallRecordingFormatWav
            else -> R.string.InuCallRecordingFormatOpus
        }
    )

    companion object {
        private val TOGGLE_RECORDING = InuUtils.generateId()
        private val TOGGLE_SOUNDPAD = InuUtils.generateId()
        private val BUTTON_RECORDINGS = InuUtils.generateId()
        private val BUTTON_FORMAT = InuUtils.generateId()
        private val BUTTON_BITRATE = InuUtils.generateId()
        private val BUTTON_ADD_SOUND = InuUtils.generateId()
        private const val CUSTOM_SOUND_BASE = 71_000
        private const val REQUEST_SOUND = 71_400

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "calls",
            titleRes = R.string.InuCallsSettings,
            iconRes = R.drawable.msg_voice_headphones,
            factory = ::CallsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("calls-recording", R.string.InuCallRecordingEnable, TOGGLE_RECORDING),
                SearchRegistry.Entry("calls-soundpad", R.string.InuCallSoundpadEnable, TOGGLE_SOUNDPAD),
                SearchRegistry.Entry("calls-soundpad-add", R.string.InuCallSoundpadAdd, BUTTON_ADD_SOUND),
            ),
        )
    }
}
