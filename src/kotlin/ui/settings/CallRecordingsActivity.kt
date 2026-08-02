package desu.mintgram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.collection.LongSparseArray
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.calls.CallRecordingHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallRecordingsActivity : SettingsPageActivity() {
    private var player: MediaPlayer? = null
    private var activeFile: File? = null
    private val durationCache = HashMap<String, Int>()
    private val dateFormat by lazy { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCallRecordingsMenu)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun onFragmentDestroy() {
        releasePlayer()
        super.onFragmentDestroy()
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: parentActivity ?: return
        val recordings = CallRecordingHelper.getSavedRecordings(ctx)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCallRecordingsSaved)))
        if (recordings.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuCallRecordingsEmpty)))
            return
        }
        recordings.forEachIndexed { index, file ->
            val cell = RecordingCell(ctx).apply {
                bind(file, isPlaying(file))
                setOnClickListener { togglePlayback(file) }
                shareButton.setOnClickListener { openSharePicker(file) }
            }
            items.add(UItem.asCustom(cell, 72).apply { id = ROW_BASE + index })
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuCallRecordingsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) = Unit

    private fun togglePlayback(file: File) {
        val current = player
        if (activeFile?.absolutePath == file.absolutePath && current != null) {
            try {
                if (current.isPlaying) current.pause() else current.start()
                listView?.adapter?.update(false)
            } catch (_: Exception) {
                playbackFailed()
            }
            return
        }

        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releasePlayer()
                    listView?.adapter?.update(false)
                }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    listView?.adapter?.update(false)
                    playbackFailed()
                    true
                }
                prepare()
                start()
            }
            activeFile = file
            listView?.adapter?.update(false)
        } catch (_: Exception) {
            releasePlayer()
            playbackFailed()
        }
    }

    private fun isPlaying(file: File): Boolean {
        if (activeFile?.absolutePath != file.absolutePath) return false
        return try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        activeFile = null
    }

    private fun playbackFailed() {
        BulletinFactory.of(this)
            .createErrorBulletin(LocaleController.getString(R.string.InuCallRecordingsPlaybackFailed))
            .show()
    }

    private fun openSharePicker(file: File) {
        val ctx = parentActivity ?: return
        val account = accountInstance
        val sheet = object : ShareAlert(ctx, null, null, false, null, false) {
            override fun onSend(
                dids: LongSparseArray<TLRPC.Dialog>,
                count: Int,
                topic: TLRPC.TL_forumTopic?,
                showToast: Boolean,
            ) {
                for (i in 0 until dids.size()) {
                    val did = dids.keyAt(i)
                    SendMessagesHelper.prepareSendingDocument(
                        account, file.absolutePath, file.absolutePath, null, null,
                        mimeType(file), did,
                        null, null, null, null, null,
                        true, 0, null, null, 0, false,
                    )
                }
                if (dids.size() == 1) openChat(dids.keyAt(0))
            }
        }
        showDialog(sheet)
    }

    private fun openChat(did: Long) {
        val args = Bundle().apply {
            putBoolean("scrollToTopOnResume", true)
            when {
                DialogObject.isEncryptedDialog(did) ->
                    putInt("enc_id", DialogObject.getEncryptedChatId(did))
                DialogObject.isUserDialog(did) -> putLong("user_id", did)
                else -> putLong("chat_id", -did)
            }
        }
        if (messagesController.checkCanOpenChat(args, this)) {
            presentFragment(ChatActivity(args))
        }
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    private fun durationSeconds(file: File): Int = durationCache.getOrPut(file.absolutePath) {
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L).div(1_000L).toInt()
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun displayName(file: File): String {
        val stem = file.nameWithoutExtension.removePrefix("Mintgram_Call_")
        return stem.replace(
            Regex("""_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}(?:_\d+)?$"""),
            "",
        ).replace('_', ' ').ifEmpty { file.nameWithoutExtension }
    }

    private inner class RecordingCell(context: Context) : FrameLayout(context) {
        private val playButton = ImageView(context)
        private val titleView = TextView(context)
        private val subtitleView = TextView(context)
        val shareButton = ImageView(context)

        init {
            setWillNotDraw(false)
            minimumHeight = AndroidUtilities.dp(72f)
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider))
            foreground = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider),
                Theme.RIPPLE_MASK_ALL,
            )

            playButton.scaleType = ImageView.ScaleType.CENTER
            addView(playButton, LayoutHelper.createFrame(48, 48f, Gravity.START or Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            titleView.setSingleLine(true)
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 24f, Gravity.TOP or Gravity.START, 64f, 13f, 58f, 0f))

            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider))
            subtitleView.setSingleLine(true)
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 22f, Gravity.TOP or Gravity.START, 64f, 39f, 58f, 0f))

            shareButton.setImageResource(R.drawable.msg_shareout)
            shareButton.setColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourceProvider),
                PorterDuff.Mode.MULTIPLY,
            )
            shareButton.scaleType = ImageView.ScaleType.CENTER
            shareButton.background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider),
                Theme.RIPPLE_MASK_CIRCLE_20DP,
            )
            shareButton.contentDescription = LocaleController.getString(R.string.InuCallRecordingsShare)
            addView(shareButton, LayoutHelper.createFrame(48, 48f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        }

        fun bind(file: File, playing: Boolean) {
            playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            playButton.setColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon, resourceProvider),
                PorterDuff.Mode.MULTIPLY,
            )
            playButton.contentDescription = LocaleController.getString(
                if (playing) R.string.InuCallRecordingsPause else R.string.InuCallRecordingsPlay,
            )
            titleView.text = displayName(file)
            val duration = durationSeconds(file)
            subtitleView.text = buildString {
                append(dateFormat.format(Date(file.lastModified())))
                append("  •  ")
                if (duration > 0) {
                    append(AndroidUtilities.formatShortDuration(duration))
                    append("  •  ")
                }
                append(AndroidUtilities.formatFileSize(file.length()))
                append("  •  ")
                append(file.extension.uppercase(Locale.US))
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(
                AndroidUtilities.dp(64f).toFloat(),
                height - 1f,
                width.toFloat(),
                height.toFloat(),
                Theme.dividerPaint,
            )
        }
    }

    companion object {
        private const val ROW_BASE = 51_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "call-recordings",
            titleRes = R.string.InuCallRecordingsMenu,
            iconRes = R.drawable.msg_voice_headphones,
            factory = ::CallRecordingsActivity,
        )
    }
}
