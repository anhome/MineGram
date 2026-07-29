package desu.mintgram.helpers.feed

import org.telegram.messenger.MessagesController
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors the real per-channel unread state (`TLRPC.Dialog.unread_count`/`top_message`, already
 * in memory via `dialogs_dict` — no separate counter of our own) and marks channels read for real
 * via the stock `MessagesController.markDialogAsRead`.
 */
object FeedUnreadTracker {
    private val pendingMaxRead = ConcurrentHashMap<Long, Int>()

    fun unreadCount(account: Int, dialogId: Long): Int =
        MessagesController.getInstance(account).dialogs_dict[dialogId]?.unread_count ?: 0

    fun totalUnread(account: Int, dialogIds: List<Long>): Int =
        dialogIds.sumOf { unreadCount(account, it) }

    /** Call when a post becomes visible on screen; accumulates, doesn't hit the network itself — see [flushPending]. */
    fun onPostSeen(dialogId: Long, messageId: Int) {
        val current = pendingMaxRead[dialogId] ?: 0
        if (messageId > current) pendingMaxRead[dialogId] = messageId
    }

    /** Call periodically (e.g. every ~1s) while the feed is visible to commit accumulated read-positions. */
    fun flushPending(account: Int) {
        if (pendingMaxRead.isEmpty()) return
        val controller = MessagesController.getInstance(account)
        val toFlush = HashMap(pendingMaxRead)
        pendingMaxRead.clear()
        for ((dialogId, maxId) in toFlush) {
            controller.markDialogAsRead(dialogId, maxId, maxId, 0, false, 0L, 0, true, 0)
        }
    }

    fun markAllRead(account: Int, dialogIds: List<Long>) {
        val controller = MessagesController.getInstance(account)
        for (dialogId in dialogIds) {
            val dialog = controller.dialogs_dict[dialogId] ?: continue
            if (dialog.unread_count <= 0) continue
            controller.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, 0, false, 0L, 0, true, 0)
        }
    }
}
