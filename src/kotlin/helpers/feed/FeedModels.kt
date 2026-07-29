package desu.mintgram.helpers.feed

/** Lightweight pointer into an already-locally-synced message — no content is duplicated here. */
data class FeedPost(val dialogId: Long, val messageId: Int, val date: Int)

data class FeedChannel(
    val dialogId: Long,
    val title: String,
    val unreadCount: Int,
    val excluded: Boolean,
)
