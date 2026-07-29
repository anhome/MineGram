package desu.mintgram.ui

import android.content.Context
import android.view.View
import desu.mintgram.helpers.security.MessageHistoryHelper
import org.telegram.messenger.ContactsController
import org.telegram.messenger.DialogObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.Components.UniversalRecyclerView

// Local-only viewer for messages captured by MessageHistoryHelper before a delete update
// removed them from messages_v2. Shows text/media-type + sender + when it was sent and deleted.
class MessageHistoryActivity(
    private val dialogId: Long,
) : UniversalFragment() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDeletedMessagesTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val entries = MessageHistoryHelper.getDeletedMessages(currentAccount, dialogId)
        if (entries.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuDeletedMessagesEmpty)))
            return
        }
        for (entry in entries) {
            val sender = resolveSenderName(entry.senderId)
            val when_ = LocaleController.formatDateAudio(entry.date.toLong(), true)
            RowFactory.of(entry.message.id, "$sender · $when_", summarize(entry.message)).let(items::add)
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        // read-only list for now - rows already show full text (multiline), nothing to drill into
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

    private fun resolveSenderName(senderId: Long): String {
        if (senderId == 0L) return LocaleController.getString(R.string.InuDeletedMessagesUnknownSender)
        return if (DialogObject.isUserDialog(senderId)) {
            val user = messagesController.getUser(senderId)
            if (user != null) ContactsController.formatName(user.first_name, user.last_name) else senderId.toString()
        } else {
            messagesController.getChat(-senderId)?.title ?: senderId.toString()
        }
    }

    private fun summarize(message: TLRPC.Message): String {
        if (!message.message.isNullOrEmpty()) return message.message
        val label = when {
            MessageObject.isStickerMessage(message) -> R.string.AttachSticker
            MessageObject.isGifMessage(message) -> R.string.AttachGif
            MessageObject.isRoundVideoMessage(message) -> R.string.AttachRound
            MessageObject.isVoiceMessage(message) -> R.string.AttachAudio
            MessageObject.isVideoMessage(message) -> R.string.AttachVideo
            MessageObject.isMusicMessage(message) -> R.string.AttachAudio
            message.media is TLRPC.TL_messageMediaPhoto -> R.string.AttachPhoto
            message.media is TLRPC.TL_messageMediaDocument -> R.string.AttachDocument
            message.media is TLRPC.TL_messageMediaGeo || message.media is TLRPC.TL_messageMediaGeoLive -> R.string.AttachLocation
            message.media is TLRPC.TL_messageMediaContact -> R.string.AttachContact
            message.media is TLRPC.TL_messageMediaPoll -> R.string.Poll
            else -> R.string.InuDeletedMessagesEmptyContent
        }
        return LocaleController.getString(label)
    }

    class RowFactory : UItem.UItemFactory<TextDetailSettingsCell>() {
        companion object {
            init {
                setup(RowFactory())
            }

            @JvmStatic
            fun of(id: Int, label: CharSequence, value: CharSequence): UItem {
                return UItem.ofFactory(RowFactory::class.java).apply {
                    this.id = id
                    this.text = label
                    this.subtext = value
                }
            }
        }

        override fun createView(
            context: Context,
            listView: RecyclerListView?,
            currentAccount: Int,
            classGuid: Int,
            resourcesProvider: Theme.ResourcesProvider?,
        ): TextDetailSettingsCell {
            return TextDetailSettingsCell(context).apply {
                setMultilineDetail(true)
            }
        }

        override fun bindView(
            view: View,
            item: UItem,
            divider: Boolean,
            adapter: UniversalAdapter,
            listView: UniversalRecyclerView?,
        ) {
            val cell = view as TextDetailSettingsCell
            val label = Emoji.replaceEmoji(item.text, cell.textView.paint.fontMetricsInt, false)
            val value = Emoji.replaceEmoji(item.subtext, cell.valueTextView.paint.fontMetricsInt, false)
            cell.setTextAndValue(label, value, divider)
        }
    }
}
