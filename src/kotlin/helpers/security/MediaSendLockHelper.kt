package desu.mintgram.helpers.security

import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC

// Gates sending anything that isn't plain text - photos, videos, documents, voice/round
// messages, gifs/stickers, locations, polls, forwards - behind a code word or biometric.
// Hooked at SendMessagesHelper's two real send entry points (SendMessageParams-based compose
// send, and the ArrayList<MessageObject> forward send) rather than any single UI button, so it
// covers every path that actually dispatches a message regardless of which screen triggered it.
object MediaSendLockHelper : TimedCodeLock("mintgram_media_send_lock") {
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
        if (!isMediaParams(params) || !needsChallenge()) return false
        challenge(R.string.InuMediaSendLockPrompt, retry)
        return true
    }

    @JvmStatic
    fun gateForward(messages: ArrayList<MessageObject>?, retry: Runnable): Boolean {
        if (messages == null || messages.none { isMediaMessage(it) } || !needsChallenge()) return false
        challenge(R.string.InuMediaSendLockPrompt, retry)
        return true
    }
}
