package desu.mintgram.helpers.feed

import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.TLRPC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds a merged chronological timeline across several channels from already-synced local
 * messages only (`SELECT` against stock `messages_v2`) — no network calls. Deeper history for
 * channels whose local cache doesn't go back far enough is [FeedBackfillCoordinator]'s job.
 */
object FeedTimelineLoader {
    data class Cursor(val date: Int, val dialogId: Long, val messageId: Int)

    /** Posts strictly older than [before] (or the newest posts if null), newest first — pointers only, no content. */
    fun loadOlderPage(account: Int, dialogIds: List<Long>, before: Cursor?, limit: Int = 30): List<FeedPost> {
        if (dialogIds.isEmpty()) return emptyList()
        return onStorageQueue(account, emptyList()) { storage ->
            val cursor = query(storage, "mid, uid, date", dialogIds, before, limit)
            val result = ArrayList<FeedPost>()
            try {
                while (cursor.next()) {
                    result.add(FeedPost(dialogId = cursor.longValue(1), messageId = cursor.intValue(0), date = cursor.intValue(2)))
                }
            } finally {
                cursor.dispose()
            }
            result
        }
    }

    /** Same as [loadOlderPage] but deserializes the full stored message — what the feed UI actually renders. */
    fun loadOlderMessages(account: Int, dialogIds: List<Long>, before: Cursor?, limit: Int = 30): List<TLRPC.Message> {
        if (dialogIds.isEmpty()) return emptyList()
        return onStorageQueue(account, emptyList()) { storage ->
            val cursor = query(storage, "uid, data", dialogIds, before, limit)
            val result = ArrayList<TLRPC.Message>()
            try {
                while (cursor.next()) {
                    val dialogId = cursor.longValue(0)
                    val buffer = cursor.byteBufferValue(1) ?: continue
                    try {
                        buffer.position(0)
                        val message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false)
                        if (message != null) {
                            message.dialog_id = dialogId // "custom" field, not part of the serialized TL bytes
                            result.add(message)
                        }
                    } finally {
                        buffer.reuse()
                    }
                }
            } finally {
                cursor.dispose()
            }
            result
        }
    }

    private fun query(
        storage: MessagesStorage,
        columns: String,
        dialogIds: List<Long>,
        before: Cursor?,
        limit: Int,
    ) = run {
        val placeholders = dialogIds.joinToString(",") { "?" }
        val args = ArrayList<Any>(dialogIds)
        val sql = StringBuilder("SELECT $columns FROM messages_v2 WHERE uid IN ($placeholders)")
        if (before != null) {
            sql.append(" AND (date < ? OR (date = ? AND (uid < ? OR (uid = ? AND mid < ?))))")
            args.add(before.date)
            args.add(before.date)
            args.add(before.dialogId)
            args.add(before.dialogId)
            args.add(before.messageId)
        }
        sql.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ?")
        args.add(limit)
        storage.database.queryFinalized(sql.toString(), *args.toTypedArray())
    }

    // Same blocking-round-trip-through-storageQueue idiom as MessageHistoryHelper.onStorageQueue —
    // the SQLite handle isn't safe to touch concurrently from an arbitrary calling thread.
    private fun <T> onStorageQueue(account: Int, default: T, block: (MessagesStorage) -> T): T {
        val storage = MessagesStorage.getInstance(account)
        val latch = CountDownLatch(1)
        val ref = AtomicReference(default)
        storage.storageQueue.postRunnable {
            try {
                ref.set(block(storage))
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return ref.get()
    }
}
