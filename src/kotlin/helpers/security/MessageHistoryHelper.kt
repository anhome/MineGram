package desu.mintgram.helpers.security

import android.util.Log
import desu.mintgram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLRPC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MintgramHistory"

// Keeps a local-only copy of message content that would otherwise vanish: what a message said
// before it got deleted, and what it said before each edit. Nothing is sent anywhere - it's purely
// a local cache, gated by InuConfig and readable only from MessageHistoryActivity.
object MessageHistoryHelper {
    private fun serialize(message: TLRPC.Message): NativeByteBuffer {
        val buffer = NativeByteBuffer(message.objectSize)
        message.serializeToStream(buffer)
        return buffer
    }

    private fun deserialize(bytes: NativeByteBuffer): TLRPC.Message {
        bytes.position(0)
        return TLRPC.Message.TLdeserialize(bytes, bytes.readInt32(false), false)
    }

    // Called from MessagesStorage.markMessagesAsDeletedInternal, right before the row is dropped
    // from messages_v2 - `message` is still the pre-deletion content read from that row.
    @JvmStatic
    fun saveDeletedMessage(account: Int, dialogId: Long, message: TLRPC.Message) {
        Log.d(TAG, "saveDeletedMessage called: account=$account dialogId=$dialogId messageId=${message.id} enabled=${InuConfig.SAVE_DELETED_MESSAGES.value}")
        if (!InuConfig.SAVE_DELETED_MESSAGES.value) return
        if (message.id <= 0) return // ignore local-only/not-yet-sent messages

        val storage = MessagesStorage.getInstance(account)
        val senderId = MessageObject.getFromChatId(message)
        val data = serialize(message)
        val now = ConnectionsManager.getInstance(account).currentTime
        storage.storageQueue.postRunnable {
            try {
                val state = storage.database.executeFast(
                    "INSERT OR IGNORE INTO inu_deleted_messages VALUES(?, ?, ?, ?, ?, ?, ?)"
                )
                state.bindInteger(1, account)
                state.bindLong(2, dialogId)
                state.bindInteger(3, message.id)
                state.bindLong(4, senderId)
                state.bindInteger(5, message.date)
                state.bindInteger(6, now)
                state.bindByteBuffer(7, data)
                state.step()
                state.dispose()
                Log.d(TAG, "saveDeletedMessage: inserted messageId=${message.id}")
            } catch (e: Exception) {
                Log.e(TAG, "saveDeletedMessage: DB write failed for messageId=${message.id}", e)
            } finally {
                data.reuse()
            }
        }
    }

    // Called from MessagesController when a TL_updateEditMessage/TL_updateEditChannelMessage
    // arrives, with the *new* message content. Looks up what's currently stored (the pre-edit
    // content) and, if it actually changed, snapshots it before the new content overwrites it.
    @JvmStatic
    fun captureEdit(account: Int, newMessage: TLRPC.Message) {
        if (!InuConfig.SAVE_EDITED_MESSAGES.value) return
        if (newMessage.id <= 0) return

        val dialogId = MessageObject.getDialogId(newMessage)
        val storage = MessagesStorage.getInstance(account)
        val oldMessage = storage.getMessage(dialogId, newMessage.id.toLong()) ?: return
        if (oldMessage.message == newMessage.message) return // no visible text change (pin/reactions/etc. also hit this path)

        val data = serialize(oldMessage)
        val now = ConnectionsManager.getInstance(account).currentTime
        storage.storageQueue.postRunnable {
            try {
                val state = storage.database.executeFast(
                    "INSERT INTO inu_edited_messages(account, dialog_id, message_id, edit_date, captured_date, data) VALUES(?, ?, ?, ?, ?, ?)"
                )
                state.bindInteger(1, account)
                state.bindLong(2, dialogId)
                state.bindInteger(3, oldMessage.id)
                state.bindInteger(4, oldMessage.edit_date)
                state.bindInteger(5, now)
                state.bindByteBuffer(6, data)
                state.step()
                state.dispose()
            } finally {
                data.reuse()
            }
        }
    }

    // Whether a deleted message's row should be kept in messages_v2 instead of hard-deleted, so
    // stock's own loader naturally shows it again on every future load. Mirrors
    // DeletedMessageGhostHelper's ghostability check but works on the raw TL message read from the
    // DB row, not a live MessageObject (isSending/scheduled don't apply to an already-committed row).
    @JvmStatic
    fun isGhostable(message: TLRPC.Message): Boolean {
        if (!InuConfig.SAVE_DELETED_MESSAGES.value) return false
        val action = message.action
        if (action != null && action !is TLRPC.TL_messageActionEmpty) return false
        return true
    }

    @JvmStatic
    fun getGhostedMessageIds(account: Int, dialogId: Long): Set<Int> =
        onStorageQueue(account, emptySet()) { storage ->
            val result = HashSet<Int>()
            val cursor = storage.database.queryFinalized(
                "SELECT message_id FROM inu_deleted_messages WHERE account = ? AND dialog_id = ?",
                account, dialogId,
            )
            try {
                while (cursor.next()) result.add(cursor.intValue(0))
            } finally {
                cursor.dispose()
            }
            result
        }

    data class DeletedMessage(
        val dialogId: Long,
        val senderId: Long,
        val date: Int,
        val deletedDate: Int,
        val message: TLRPC.Message,
    )

    data class EditRevision(
        val editDate: Int,
        val capturedDate: Int,
        val message: TLRPC.Message,
    )

    // All reads round-trip through storageQueue (like MessagesStorage.getMessage) - the SQLite
    // handle isn't safe to touch concurrently from whatever thread calls into this helper.
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

    @JvmStatic
    fun getDeletedMessages(account: Int, dialogId: Long, limit: Int = 200): List<DeletedMessage> =
        onStorageQueue(account, emptyList()) { storage ->
            val result = ArrayList<DeletedMessage>()
            val cursor = storage.database.queryFinalized(
                "SELECT sender_id, date, deleted_date, data FROM inu_deleted_messages WHERE account = ? AND dialog_id = ? ORDER BY deleted_date DESC LIMIT ?",
                account, dialogId, limit,
            )
            try {
                while (cursor.next()) {
                    val buffer = cursor.byteBufferValue(3) ?: continue
                    try {
                        result.add(
                            DeletedMessage(
                                dialogId = dialogId,
                                senderId = cursor.longValue(0),
                                date = cursor.intValue(1),
                                deletedDate = cursor.intValue(2),
                                message = deserialize(buffer),
                            )
                        )
                    } finally {
                        buffer.reuse()
                    }
                }
            } finally {
                cursor.dispose()
            }
            result
        }

    @JvmStatic
    fun getEditHistory(account: Int, dialogId: Long, messageId: Int): List<EditRevision> =
        onStorageQueue(account, emptyList()) { storage ->
            val result = ArrayList<EditRevision>()
            val cursor = storage.database.queryFinalized(
                "SELECT edit_date, captured_date, data FROM inu_edited_messages WHERE account = ? AND dialog_id = ? AND message_id = ? ORDER BY captured_date ASC",
                account, dialogId, messageId,
            )
            try {
                while (cursor.next()) {
                    val buffer = cursor.byteBufferValue(2) ?: continue
                    try {
                        result.add(
                            EditRevision(
                                editDate = cursor.intValue(0),
                                capturedDate = cursor.intValue(1),
                                message = deserialize(buffer),
                            )
                        )
                    } finally {
                        buffer.reuse()
                    }
                }
            } finally {
                cursor.dispose()
            }
            result
        }

    @JvmStatic
    fun hasEditHistory(account: Int, dialogId: Long, messageId: Int): Boolean =
        onStorageQueue(account, false) { storage ->
            val cursor = storage.database.queryFinalized(
                "SELECT 1 FROM inu_edited_messages WHERE account = ? AND dialog_id = ? AND message_id = ? LIMIT 1",
                account, dialogId, messageId,
            )
            try {
                cursor.next()
            } finally {
                cursor.dispose()
            }
        }
}
