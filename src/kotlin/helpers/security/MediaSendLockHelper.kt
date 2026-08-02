package desu.mintgram.helpers.security

import android.os.SystemClock
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC

// Gates sending anything that isn't plain text - photos, videos, documents, voice/round
// messages, gifs/stickers, locations, polls, forwards - behind a code word or biometric.
// Hooked at SendMessagesHelper's real send entry points (media-batch preparation,
// SendMessageParams-based compose send, and the ArrayList<MessageObject> forward send) rather
// than any single UI button, so it covers every path that actually dispatches a message
// regardless of which screen triggered it.
object MediaSendLockHelper : TimedCodeLock("mintgram_media_send_lock") {
    private const val BATCH_AUTHORIZATION_TTL_MS = 2 * 60_000L

    private data class BatchAuthorization(
        val peer: Long,
        var remaining: Int,
        val expiresAt: Long,
    )

    private var batchAuthorization: BatchAuthorization? = null

    @JvmStatic
    fun isMediaParams(params: SendMessagesHelper.SendMessageParams): Boolean =
        params.photo != null || params.document != null || params.location != null ||
            params.poll != null || params.game != null || params.invoice != null || params.todo != null

    @JvmStatic
    fun isMediaMessage(message: MessageObject): Boolean {
        val media = message.messageOwner?.media ?: return false
        return media !is TLRPC.TL_messageMediaEmpty && media !is TLRPC.TL_messageMediaWebPage
    }

    // Returns true if the send was intercepted (caller must return without sending); [retry] is
    // re-invoked - and expected to reach this same gate again, now passing thanks to the
    // one-shot bypass - once the challenge succeeds.
    @JvmStatic
    fun gateSend(params: SendMessagesHelper.SendMessageParams, retry: Runnable): Boolean {
        if (!isMediaParams(params) || consumeBatchAuthorization(params.peer) || !needsChallenge()) return false
        challenge(R.string.InuMediaSendLockPrompt, retry)
        return true
    }

    // Telegram prepares an album as one operation but dispatches every item through sendMessage
    // separately. Authorize the complete operation before preparation starts, then consume one
    // permit per generated media message so the album isn't reduced to its first item.
    @JvmStatic
    fun gateMediaBatch(mediaCount: Int, peer: Long, retry: Runnable): Boolean {
        if (mediaCount <= 1 || !needsChallenge()) return false
        challenge(R.string.InuMediaSendLockPrompt) {
            authorizeBatch(peer, mediaCount)
            retry.run()
        }
        return true
    }

    @JvmStatic
    fun gateForward(messages: ArrayList<MessageObject>?, retry: Runnable): Boolean {
        if (messages == null || messages.none { isMediaMessage(it) } || !needsChallenge()) return false
        challenge(R.string.InuMediaSendLockPrompt, retry)
        return true
    }

    @Synchronized
    private fun authorizeBatch(peer: Long, mediaCount: Int) {
        batchAuthorization = BatchAuthorization(
            peer = peer,
            remaining = mediaCount,
            expiresAt = SystemClock.elapsedRealtime() + BATCH_AUTHORIZATION_TTL_MS,
        )
    }

    @Synchronized
    private fun consumeBatchAuthorization(peer: Long): Boolean {
        val authorization = batchAuthorization ?: return false
        if (authorization.peer != peer || SystemClock.elapsedRealtime() >= authorization.expiresAt) {
            batchAuthorization = null
            return false
        }
        authorization.remaining--
        if (authorization.remaining <= 0) {
            batchAuthorization = null
        }
        return true
    }
}
