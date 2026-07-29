package desu.mintgram.ui.settings.feed

import android.view.View
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.feed.FeedChannelRegistry
import desu.mintgram.helpers.feed.FeedChatHelper
import desu.mintgram.helpers.feed.FeedConfigHelper
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class FeedChannelsActivity : SettingsPageActivity() {
    private var channels = FeedChannelRegistry.eligibleChannels(currentAccount).toMutableList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuFeed)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asRippleCheck(TOGGLE_INCLUDE_ARCHIVED, LocaleController.getString(R.string.InuFeedIncludeArchived))
                .setChecked(FeedConfigHelper.includeArchived)
        )
        items.add(
            UItem.asRippleCheck(TOGGLE_TAB_ENABLED, LocaleController.getString(R.string.InuFeedTabEnabled))
                .setChecked(FeedConfigHelper.tabEnabled)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFeedTabEnabledInfo)))

        items.add(UItem.asButton(BUTTON_SELECT_ALL, LocaleController.getString(R.string.InuFeedSelectAll)))
        items.add(UItem.asButton(BUTTON_DESELECT_ALL, LocaleController.getString(R.string.InuFeedDeselectAll)))
        items.add(UItem.asShadow(null))

        // Also reachable via long-press on the Chats tab (MainTabsHelper.openChatsLongPressMenu).
        items.add(UItem.asButton(BUTTON_PREVIEW, LocaleController.getString(R.string.InuFeedPreview)))
        items.add(UItem.asShadow(null))

        if (channels.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuFeedNoChannels)))
        } else {
            for ((i, channel) in channels.withIndex()) {
                items.add(
                    UItem.asRippleCheck(ITEM_BASE + i, channel.title).setChecked(!channel.excluded)
                )
            }
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_INCLUDE_ARCHIVED -> {
                val new = !FeedConfigHelper.includeArchived
                FeedConfigHelper.includeArchived = new
                (view as? TextCheckCell)?.isChecked = new
                channels = FeedChannelRegistry.eligibleChannels(currentAccount).toMutableList()
                listView.adapter.update(true)
            }

            TOGGLE_TAB_ENABLED -> {
                val new = !FeedConfigHelper.tabEnabled
                FeedConfigHelper.tabEnabled = new
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_SELECT_ALL -> {
                FeedConfigHelper.saveExcluded(emptySet())
                channels = channels.map { it.copy(excluded = false) }.toMutableList()
                listView.adapter.update(true)
            }

            BUTTON_DESELECT_ALL -> {
                FeedConfigHelper.saveExcluded(channels.map { it.dialogId }.toSet())
                channels = channels.map { it.copy(excluded = true) }.toMutableList()
                listView.adapter.update(true)
            }

            BUTTON_PREVIEW -> FeedChatHelper.open(this)

            else -> {
                val idx = item.id - ITEM_BASE
                if (idx in channels.indices) {
                    val channel = channels[idx]
                    val newExcluded = !channel.excluded
                    FeedConfigHelper.setExcluded(channel.dialogId, newExcluded)
                    channels[idx] = channel.copy(excluded = newExcluded)
                    (view as? TextCheckCell)?.isChecked = !newExcluded
                }
            }
        }
    }

    companion object {
        private val TOGGLE_INCLUDE_ARCHIVED = InuUtils.generateId()
        private val TOGGLE_TAB_ENABLED = InuUtils.generateId()
        private val BUTTON_SELECT_ALL = InuUtils.generateId()
        private val BUTTON_DESELECT_ALL = InuUtils.generateId()
        private val BUTTON_PREVIEW = InuUtils.generateId()
        private const val ITEM_BASE = 22_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "feed-channels",
            titleRes = R.string.InuFeed,
            iconRes = R.drawable.msg_discussion,
            factory = ::FeedChannelsActivity,
        )
    }
}
