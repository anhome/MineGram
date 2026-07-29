package desu.mintgram.ui.settings.speech

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import desu.mintgram.InuConfig
import desu.mintgram.helpers.speech.RecognitionModel
import desu.mintgram.helpers.speech.SpeechModels
import desu.mintgram.helpers.speech.VoskModelStorage
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.DialogRadioCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class SpeechRecognitionLanguageActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRecognitionLanguage)

    private val cells = HashMap<String, DialogRadioCell>()

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val current = InuConfig.SPEECH_RECOGNITION_LANGUAGE.value
        items.add(UItem.asCustom(cellFor("none") {
            it.setTextAndValue(LocaleController.getString(R.string.InuRecognitionLanguageOff), "", current == "none", true)
        }))
        items.add(UItem.asShadow(null))
        for ((i, model) in SpeechModels.ALL.withIndex()) {
            val title = SpeechModels.displayName(model.language)
            val subtitle = if (VoskModelStorage.isDownloaded(model.language)) "" else AndroidUtilities.formatFileSize(model.sizeBytes)
            items.add(UItem.asCustom(cellFor(model.language) {
                it.setTextAndValue(title, subtitle, model.language == current, i < SpeechModels.ALL.size - 1)
            }))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuRecognitionInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {}

    private fun select(language: String) {
        val current = InuConfig.SPEECH_RECOGNITION_LANGUAGE.value
        if (language == current) return
        if (language == "none" || VoskModelStorage.isDownloaded(language)) {
            applySelection(language)
            return
        }
        val model = SpeechModels.find(language) ?: return
        showDownloadConfirm(language, model)
    }

    private fun showDownloadConfirm(language: String, model: RecognitionModel) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(LocaleController.getString(R.string.InuMissingLanguageModel))
        builder.setMessage(
            LocaleController.formatString(
                R.string.InuModelDownloadInfo,
                SpeechModels.displayName(language),
                AndroidUtilities.formatFileSize(model.sizeBytes),
            )
        )
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        builder.setPositiveButton(LocaleController.getString(R.string.InuModelDownload)) { _, _ ->
            startDownload(language, model)
        }
        showDialog(builder.create())
    }

    private fun startDownload(language: String, model: RecognitionModel) {
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            val accent = ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgress))
            progressTintList = accent
            progressBackgroundTintList = ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgressBackground))
        }
        val title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            gravity = Gravity.CENTER_HORIZONTAL
            text = LocaleController.getString(R.string.InuDownloadingModel)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = AndroidUtilities.dp(16f)
            setPadding(pad, pad, pad, pad)
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 12f))
            addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(context).setView(container).create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        showDialog(dialog)

        VoskModelStorage.download(
            language,
            onProgress = { progress -> progressBar.progress = (progress * 1000).toInt() },
            onComplete = {
                dialog.dismiss()
                applySelection(language)
                BulletinFactory.of(this).createSuccessBulletin(LocaleController.getString(R.string.InuModelDownloaded)).show()
            },
            onError = { _ ->
                dialog.dismiss()
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuModelDownloadError)).show()
            },
        )
    }

    private fun applySelection(language: String) {
        InuConfig.SPEECH_RECOGNITION_LANGUAGE.value = language
        cells.forEach { (code, cell) -> cell.setChecked(code == language, true) }
        listView?.adapter?.update(true)
    }

    private inline fun cellFor(code: String, configure: (DialogRadioCell) -> Unit): DialogRadioCell =
        cells.getOrPut(code) {
            DialogRadioCell(context).also {
                configure(it)
                it.tag = code
                it.background = Theme.getSelectorDrawable(false)
                it.setOnClickListener { _ -> select(code) }
            }
        }
}
