package desu.mintgram.helpers.security

import desu.mintgram.InuConfig
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

// Ghost mode: suppress outgoing read/online/typing packets at the network layer.
// The chat UI still marks things as read locally - only what we tell the server changes.
object GhostModeHelper {
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        InuConfig.GHOST_HIDE_READ_STATUS.value = enabled
        InuConfig.GHOST_HIDE_ONLINE_STATUS.value = enabled
        InuConfig.GHOST_HIDE_TYPING_STATUS.value = enabled
    }

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.GHOST_HIDE_READ_STATUS.value &&
        InuConfig.GHOST_HIDE_ONLINE_STATUS.value && InuConfig.GHOST_HIDE_TYPING_STATUS.value
    @JvmStatic
    fun isReadRequest(obj: TLObject): Boolean = obj is TLRPC.TL_messages_readHistory ||
        obj is TLRPC.TL_messages_readEncryptedHistory ||
        obj is TLRPC.TL_messages_readDiscussion ||
        obj is TLRPC.TL_messages_readMessageContents ||
        obj is TLRPC.TL_channels_readHistory ||
        obj is TLRPC.TL_channels_readMessageContents

    // Fakes the server's ack for a suppressed read request so the caller's callback chain
    // (unread counters, pts bookkeeping) doesn't stall waiting for a response that will never come.
    @JvmStatic
    fun fakeReadResponse(onComplete: RequestDelegate?) {
        val response = TLRPC.TL_messages_affectedMessages()
        response.pts = -1
        response.pts_count = 0
        onComplete?.run(response, null)
    }
}
