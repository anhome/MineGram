package desu.mintgram.ui

import android.content.Context
import android.view.View
import desu.mintgram.helpers.security.MessageHistoryHelper
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.Components.UniversalRecyclerView

// Local-only viewer for a single message's past revisions, captured by
// MessageHistoryHelper.captureEdit right before each edit overwrote the text in messages_v2.
// Oldest first, so reading top to bottom follows the message's actual edit order; the current
// (latest) text isn't repeated here since it's already visible in the chat itself.
class EditHistoryActivity(
    private val dialogId: Long,
    private val messageId: Int,
) : UniversalFragment() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuEditHistory)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val revisions = MessageHistoryHelper.getEditHistory(currentAccount, dialogId, messageId)
        if (revisions.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuEditHistoryEmpty)))
            return
        }
        for ((index, revision) in revisions.withIndex()) {
            val when_ = LocaleController.formatDateAudio(revision.editDate.toLong(), true)
            val label = LocaleController.formatString(R.string.InuEditHistoryRevision, index + 1, when_)
            val text = revision.message.message?.takeIf { it.isNotEmpty() }
                ?: LocaleController.getString(R.string.InuDeletedMessagesEmptyContent)
            RowFactory.of(index, label, text).let(items::add)
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        // read-only list - rows already show the full pre-edit text
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean = false

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
