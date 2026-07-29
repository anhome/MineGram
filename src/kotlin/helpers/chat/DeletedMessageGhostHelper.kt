package desu.mintgram.helpers.chat

import android.util.Log
import desu.mintgram.InuConfig
import desu.mintgram.helpers.security.MessageHistoryHelper
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MintgramGhost"

// Keeps a deleted message's bubble visible - like AyuGram's ghost messages - both while the chat
// is already open (the message never leaves the live adapter list) and after re-entering the chat
// (the row survives in messages_v2, see MessagesStorage.markMessagesAsDeletedInternal, so stock's
// own loader naturally reloads it in the same spot). Ghosted state itself is looked up from
// inu_deleted_messages and cached in memory per dialog; the 🗑 marker text is applied at bind time
// (ChatActivity.ChatActivityAdapter#onBindViewHolder's real message-cell branch) rather than
// persisted into the DB row, so a fresh reload never needs any extra write.
object DeletedMessageGhostHelper {
    private const val MARKER = "🗑 "

    private val ghostedByDialog = ConcurrentHashMap<Long, MutableSet<Int>>()

    private fun ghostedIdsFor(account: Int, dialogId: Long): MutableSet<Int> =
        ghostedByDialog.getOrPut(dialogId) {
            MessageHistoryHelper.getGhostedMessageIds(account, dialogId).toMutableSet()
        }

    @JvmStatic
    fun isGhosted(account: Int, dialogId: Long, message: MessageObject?): Boolean {
        if (message == null) return false
        return ghostedIdsFor(account, dialogId).contains(message.id)
    }

    // Called from the real onBindViewHolder message-cell branch for every ghosted message, live or
    // freshly reloaded. Applies the 🗑 marker once (idempotent) and reports whether a relayout is
    // needed because the text just changed.
    @JvmStatic
    fun ensureMarked(message: MessageObject): Boolean {
        val owner = message.messageOwner ?: return false
        if (owner.message?.startsWith(MARKER) == true) return false
        owner.message = if (owner.message.isNullOrEmpty()) MARKER + "Deleted message" else MARKER + owner.message
        message.caption = null
        message.resetLayout()
        return true
    }

    // Called from ChatActivity's messagesDeleted handler while the chat is open. Prevents
    // ghostable ids from reaching processDeletedMessages (so the bubble is never removed from the
    // live list) and records them in the cache so isGhosted picks them up immediately, without
    // waiting on a DB round-trip. Returns true if anything was ghosted, so the caller knows to
    // refresh visible cells.
    @JvmStatic
    fun filterGhostable(account: Int, dialogId: Long, markAsDeletedMessages: ArrayList<Int>, messages: ArrayList<MessageObject>): Boolean {
        Log.d(TAG, "filterGhostable: enabled=${InuConfig.SAVE_DELETED_MESSAGES.value} dialogId=$dialogId markAsDeletedMessages=$markAsDeletedMessages messages=${messages.map { it.id }}")
        if (!InuConfig.SAVE_DELETED_MESSAGES.value) return false
        val ghostedNow = HashSet<Int>()
        for (msg in messages) {
            val ghostable = isGhostable(msg)
            Log.d(TAG, "filterGhostable: msg.id=${msg.id} isSending=${msg.isSending} scheduled=${msg.scheduled} action=${msg.messageOwner?.action} ghostable=$ghostable")
            if (!ghostable) continue
            ensureMarked(msg)
            ghostedNow.add(msg.id)
        }
        Log.d(TAG, "filterGhostable: ghostedNow=$ghostedNow")
        if (ghostedNow.isEmpty()) return false
        ghostedIdsFor(account, dialogId).addAll(ghostedNow)
        markAsDeletedMessages.removeAll(ghostedNow)
        return true
    }

    private fun isGhostable(msg: MessageObject): Boolean {
        if (msg.isSending || msg.scheduled) return false
        val owner = msg.messageOwner ?: return false
        if (owner.action != null && owner.action !is TLRPC.TL_messageActionEmpty) return false
        if (owner.message?.startsWith(MARKER) == true) return false // already ghosted
        return true
    }
}
