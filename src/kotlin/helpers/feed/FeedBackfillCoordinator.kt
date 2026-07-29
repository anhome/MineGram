package desu.mintgram.helpers.feed

import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MintgramFeed"
private const val MAX_CONCURRENT = 4
private const val TIMEOUT_MS = 10_000L

/**
 * For channels whose locally-synced history doesn't reach far enough back for the feed to page
 * into, kicks off the same stock `MessagesController.loadMessages` call `ChatActivity` itself uses
 * to pull a chat's history in from the server.
 *
 * Stage 1 (this pass, now wired into [FeedChatHelper]): fire-and-timeout only — completion is
 * inferred from a fixed delay, not from listening to the real
 * `messagesDidLoad`/`loadingMessagesFailed` notifications per dialog. [FeedChatHelper] treats a
 * round as having made no progress for a channel if a re-query of [FeedTimelineLoader] right after
 * the round doesn't return more local messages than before — an approximation (a round covers
 * several channels at once, so a channel that genuinely had more history but whose request lagged
 * past [TIMEOUT_MS] gets the same treatment as one that's truly exhausted). Good enough for Stage
 * 2a; a proper per-dialog NotificationCenter-driven completion check would remove the guesswork.
 */
object FeedBackfillCoordinator {
    private val exhausted = ConcurrentHashMap.newKeySet<Long>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    fun isExhausted(dialogId: Long): Boolean = dialogId in exhausted
    fun isPending(dialogId: Long): Boolean = dialogId in pending

    fun markExhausted(dialogId: Long) {
        exhausted.add(dialogId)
    }

    /**
     * Kicks off backfill for up to [MAX_CONCURRENT] of [candidates] not already pending/exhausted.
     * [onRoundDone] receives exactly the dialogIds this round actually requested (a subset of
     * [candidates], possibly empty if all candidates were already pending/exhausted elsewhere) —
     * callers use that list, not [candidates], to decide what to mark exhausted afterwards.
     */
    fun runRound(account: Int, candidates: List<Long>, onRoundDone: (List<Long>) -> Unit) {
        val targets = candidates.filter { it !in pending && it !in exhausted }.take(MAX_CONCURRENT)
        if (targets.isEmpty()) {
            onRoundDone(emptyList())
            return
        }
        val controller = MessagesController.getInstance(account)
        val classGuid = ConnectionsManager.generateClassGuid()
        for (dialogId in targets) {
            pending.add(dialogId)
            Log.d(TAG, "backfill: requesting older history for dialogId=$dialogId")
            controller.loadMessages(
                dialogId, 0L, false, 20, 0, 0, true, 0,
                classGuid, MessagesController.LOAD_BACKWARD, 0, 0, 0L, 0, 0, false,
            )
        }
        AndroidUtilities.runOnUIThread({
            for (dialogId in targets) pending.remove(dialogId)
            onRoundDone(targets)
        }, TIMEOUT_MS)
    }
}
