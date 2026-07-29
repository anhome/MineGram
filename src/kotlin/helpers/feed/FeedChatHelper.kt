package desu.mintgram.helpers.feed

import android.os.Bundle
import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge between the real `ChatActivity` (in `chatMode == MODE_SEARCH, searchType == SEARCH_FEED`
 * — see the `feed-chat` stock patch) and the Stage 1 data layer.
 *
 * Mirrors `HashtagSearchController.searchHashtag`'s contract exactly — same `messagesDidLoad`
 * notification shape (`org/telegram/messenger/HashtagSearchController.java`, the
 * `NotificationCenter.messagesDidLoad` post) — so the existing stock message-insertion pipeline
 * (`ChatActivity.didReceivedNotification_messagesDidLoad`, unmodified) picks our results up as-is.
 * We never touch `ChatActivity.messages`/`chatAdapter` directly. Also mirrors its synthetic-id
 * trick: messages from different channels can collide on `mid` in a single merged list, so each
 * gets a fresh negative id local to this session, with the real `(dialogId, id)` stashed in
 * `realId`/`dialog_id` for tap-to-open.
 */
object FeedChatHelper {
    private const val PAGE_SIZE = 30

    private class Session {
        val generatedIds = HashMap<Pair<Long, Int>, Int>()
        var lastGeneratedId = -1
        var cursor: FeedTimelineLoader.Cursor? = null
    }

    private val sessions = ConcurrentHashMap<Int, Session>()

    /** Used for both the initial load and scroll-triggered pagination — same call shape either way. */
    @JvmStatic
    fun loadPage(activity: ChatActivity, account: Int, classGuid: Int, loadIndex: Int) {
        Log.d(TAG, "loadPage: called account=$account classGuid=$classGuid loadIndex=$loadIndex")
        Utilities.globalQueue.postRunnable {
            val session = sessions.getOrPut(classGuid) { Session() }
            val dialogIds = FeedChannelRegistry.eligibleChannels(account)
                .filter { !it.excluded }
                .map { it.dialogId }
            val cursor = session.cursor
            Log.d(TAG, "loadPage: dialogIds=$dialogIds cursor=$cursor")
            val rawMessages = if (dialogIds.isEmpty()) {
                emptyList()
            } else {
                FeedTimelineLoader.loadOlderMessages(account, dialogIds, cursor, PAGE_SIZE)
            }
            Log.d(TAG, "loadPage: rawMessages.size=${rawMessages.size}")

            if (rawMessages.size >= PAGE_SIZE) {
                finishPage(activity, account, classGuid, loadIndex, session, rawMessages)
                return@postRunnable
            }

            // Local cache came up short — try to pull more history in from the server for
            // channels that aren't already known to be exhausted, then re-query once.
            val backfillCandidates = dialogIds.filter { !FeedBackfillCoordinator.isExhausted(it) }
            if (backfillCandidates.isEmpty()) {
                finishPage(activity, account, classGuid, loadIndex, session, rawMessages)
                return@postRunnable
            }

            AndroidUtilities.runOnUIThread {
                FeedBackfillCoordinator.runRound(account, backfillCandidates) { targeted ->
                    Utilities.globalQueue.postRunnable {
                        val refreshed = FeedTimelineLoader.loadOlderMessages(account, dialogIds, cursor, PAGE_SIZE)
                        Log.d(TAG, "loadPage: backfill round done, targeted=$targeted rawMessages.size=${rawMessages.size} refreshed.size=${refreshed.size}")
                        if (refreshed.size <= rawMessages.size) {
                            // Approximation (see FeedBackfillCoordinator doc): a round covering several
                            // channels that yields no extra local messages is treated as all of them
                            // being exhausted, since the round doesn't report per-channel results.
                            targeted.forEach { FeedBackfillCoordinator.markExhausted(it) }
                        }
                        val finalMessages = if (refreshed.size > rawMessages.size) refreshed else rawMessages
                        finishPage(activity, account, classGuid, loadIndex, session, finalMessages)
                    }
                }
            }
        }
    }

    private fun finishPage(
        activity: ChatActivity,
        account: Int,
        classGuid: Int,
        loadIndex: Int,
        session: Session,
        rawMessages: List<TLRPC.Message>,
    ) {
        if (rawMessages.isNotEmpty()) {
            val last = rawMessages.last()
            session.cursor = FeedTimelineLoader.Cursor(last.date, last.dialog_id, last.id)
        }

        val visibleMessages = rawMessages.filterNot { FeedConfigHelper.isPostExcluded(it.dialog_id, it.id) }

        val messageObjects = ArrayList<MessageObject>(visibleMessages.size)
        for (message in visibleMessages) {
            val key = message.dialog_id to message.id
            val syntheticId = session.generatedIds.getOrPut(key) { session.lastGeneratedId-- }
            val obj = MessageObject(
                account, message, null, null, null, null, null,
                false, true, 0L, false, false, false, ChatActivity.inu_SEARCH_FEED,
            )
            message.realId = message.id
            message.id = syntheticId
            messageObjects.add(obj)
        }

        // NOT posting NotificationCenter.hashtagSearchUpdated here (that's the stock mechanism
        // that would normally set ChatActivity.endReached[0] for MODE_SEARCH) — its handler
        // unconditionally calls messagesSearchAdapter.notifyDataSetChanged(), which reaches into
        // HashtagSearchController's own per-(guid,searchType) state and NPEs since we never
        // registered anything there (confirmed by crash: HashtagSearchController.getMessages ->
        // MessagesSearchAdapter.notifyDataSetChanged -> didReceivedNotification7). Trade-off
        // accepted for Stage 2a: checkScrollForLoad will keep re-querying near the bottom once
        // the local cache and backfill are both exhausted (our own cursor just keeps returning
        // empty — harmless, not a functional bug), instead of properly latching endReached[0].
        AndroidUtilities.runOnUIThread {
            Log.d(TAG, "loadPage: posting messagesDidLoad, messageObjects.size=${messageObjects.size}")
            NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.messagesDidLoad,
                0L, messageObjects.size, messageObjects, false,
                0, 0, 0, 0, 2, true,
                classGuid, loadIndex, 0, 0,
                ChatActivity.MODE_SEARCH,
            )
            // The generic (non-early-exit) branch of didReceivedNotification_messagesDidLoad
            // never calls this itself (only 3 specific early-exit branches do — system-signup
            // message, MODE_PINNED, a thread-chat special case) — without it the skeleton
            // placeholders never go away even though `messages` gets populated correctly.
            activity.checkDispatchHideSkeletons(true)
        }
    }

    private const val TAG = "MintgramFeed"

    fun clearSession(classGuid: Int) {
        sessions.remove(classGuid)
    }

    /** Opens the feed screen — reachable from the Chats tab long-press menu and from settings. */
    fun open(fragment: BaseFragment) {
        val args = Bundle()
        args.putInt("chatMode", ChatActivity.MODE_SEARCH)
        args.putInt("searchType", ChatActivity.inu_SEARCH_FEED)
        // ChatActivity.onFragmentCreate() refuses MODE_SEARCH outright unless searchHashtag is
        // non-null (`searchType == 0 || searchingHashtag == null -> return false`) — we don't use
        // it (our own load path never touches HashtagSearchController), it just has to be non-null.
        args.putString("searchHashtag", "feed")
        fragment.presentFragment(ChatActivity(args))
    }

    /** Real (dialogId, messageId) behind a feed row's synthetic id — for tap-to-open. */
    fun realLocation(message: TLRPC.Message): Pair<Long, Int> = message.dialog_id to message.realId
}
