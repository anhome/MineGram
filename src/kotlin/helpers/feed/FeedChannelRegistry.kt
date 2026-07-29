package desu.mintgram.helpers.feed

import androidx.collection.LongSparseArray
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC

/**
 * Enumerates broadcast channels the user is actually subscribed to — purely local (reads the
 * already-synced dialog list, no network), same idiom as [desu.mintgram.helpers.dialogs.FolderHelper]'s
 * `dialogs_dict` lookups.
 */
object FeedChannelRegistry {
    fun eligibleChannels(account: Int): List<FeedChannel> {
        val controller = MessagesController.getInstance(account)
        val dict: LongSparseArray<TLRPC.Dialog> = controller.dialogs_dict
        val result = ArrayList<FeedChannel>()
        for (i in 0 until dict.size()) {
            val dialog = dict.valueAt(i) ?: continue
            if (dialog.isFolder) continue
            val dialogId = dialog.id
            if (dialogId >= 0) continue // only chats/channels, not users
            if (dialog.folder_id != 0 && !FeedConfigHelper.includeArchived) continue
            val chat = controller.getChat(-dialogId) ?: continue
            if (!ChatObject.isChannelAndNotMegaGroup(chat)) continue
            result.add(
                FeedChannel(
                    dialogId = dialogId,
                    title = chat.title.orEmpty(),
                    unreadCount = dialog.unread_count,
                    excluded = FeedConfigHelper.isExcluded(dialogId),
                )
            )
        }
        result.sortBy { it.title.lowercase() }
        return result
    }
}
