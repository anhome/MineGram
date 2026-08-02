package desu.mintgram.helpers.channels

import desu.mintgram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction
import org.telegram.ui.Components.Reactions.ReactionsUtils
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object AutoReactionHelper {
    private const val MAX_POST_AGE_SECONDS = 120

    data class Rule(
        val account: Int,
        val dialogId: Long,
        val emoji: String?,
        val documentId: Long,
    ) {
        fun visibleReaction(): VisibleReaction = if (documentId != 0L) {
            VisibleReaction.fromCustomEmoji(documentId)
        } else {
            VisibleReaction.fromEmojicon(emoji.orEmpty())
        }

        fun tlReaction(): TLRPC.Reaction = visibleReaction().toTLReaction()
    }

    data class ReactionChoice(
        val emoji: String?,
        val documentId: Long,
    ) {
        fun visibleReaction(): VisibleReaction = if (documentId != 0L) {
            VisibleReaction.fromCustomEmoji(documentId)
        } else {
            VisibleReaction.fromEmojicon(emoji.orEmpty())
        }

        fun toRule(account: Int, dialogId: Long) =
            Rule(account, dialogId, emoji, documentId)
    }

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val statePreferences by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences(
            "mintgram_auto_reactions_state",
            0,
        )
    }

    @Synchronized
    fun rules(account: Int): List<Rule> = loadRules().filter { it.account == account }

    @Synchronized
    fun rule(account: Int, dialogId: Long): Rule? =
        loadRules().firstOrNull { it.account == account && it.dialogId == dialogId }

    @Synchronized
    fun saveRule(rule: Rule) {
        val rules = loadRules().filterNot {
            it.account == rule.account && it.dialogId == rule.dialogId
        }.toMutableList()
        rules.add(rule)
        saveRules(rules)
    }

    @Synchronized
    fun removeRule(account: Int, dialogId: Long) {
        saveRules(loadRules().filterNot { it.account == account && it.dialogId == dialogId })
    }

    fun availableReactions(account: Int, dialogId: Long): List<ReactionChoice> {
        val controller = MessagesController.getInstance(account)
        val chat = controller.getChat(-dialogId) ?: return emptyList()
        if (!ChatObject.isChannelAndNotMegaGroup(chat)) return emptyList()
        val full = controller.getChatFull(chat.id) ?: return emptyList()
        val media = MediaDataController.getInstance(account)
        val reactions = ArrayList<TLRPC.Reaction>()

        when (val allowed = full.available_reactions) {
            is TLRPC.TL_chatReactionsSome -> reactions.addAll(allowed.reactions)
            is TLRPC.TL_chatReactionsAll -> {
                media.enabledReactionsList.forEach {
                    reactions.add(TLRPC.TL_reactionEmoji().apply { emoticon = it.reaction })
                }
                if (allowed.allow_custom) {
                    reactions.addAll(
                        media.recentReactions.filterIsInstance<TLRPC.TL_reactionCustomEmoji>()
                    )
                }
            }
        }

        return reactions.mapNotNull {
            when (it) {
                is TLRPC.TL_reactionEmoji ->
                    if (media.reactionsMap.containsKey(it.emoticon)) ReactionChoice(it.emoticon, 0L) else null
                is TLRPC.TL_reactionCustomEmoji -> ReactionChoice(null, it.document_id)
                else -> null
            }
        }.distinctBy { it.emoji ?: "custom:${it.documentId}" }
    }

    fun onNewMessage(message: MessageObject, account: Int) {
        val owner = message.messageOwner ?: return
        if (!owner.post || owner.out || owner.action != null || owner.id <= 0) return
        if (!message.isReactionsAvailable) return

        val dialogId = message.dialogId
        val rule = rule(account, dialogId) ?: return
        if (owner.id <= lastHandledId(account, dialogId)) return

        val connections = ConnectionsManager.getInstance(account)
        if (connections.connectionState != ConnectionsManager.ConnectionStateConnected &&
            connections.connectionState != ConnectionsManager.ConnectionStateUpdating
        ) return
        if (abs(connections.currentTime - owner.date) > MAX_POST_AGE_SECONDS) return
        if (!isStillAllowed(account, dialogId, rule.tlReaction())) return
        if (alreadyChosen(owner, rule.tlReaction())) {
            markHandled(rule, owner.id)
            return
        }

        val key = "$account:$dialogId:${owner.id}"
        if (!pending.add(key)) return
        val request = TLRPC.TL_messages_sendReaction().apply {
            peer = MessagesController.getInstance(account).getInputPeer(dialogId)
            msg_id = owner.id
            flags = flags or 1
            reaction.add(rule.tlReaction())
        }
        connections.sendRequest(request) { response, error ->
            pending.remove(key)
            if (error == null && response is TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates(response, false)
                markHandled(rule, owner.id)
            }
        }
    }

    private fun isStillAllowed(account: Int, dialogId: Long, reaction: TLRPC.Reaction): Boolean {
        // ChatFull is not always restored into the in-memory cache after a process restart.
        // The choice was validated when configured; let Telegram revalidate it server-side.
        val full = MessagesController.getInstance(account).getChatFull(-dialogId) ?: return true
        return when (val allowed = full.available_reactions) {
            is TLRPC.TL_chatReactionsSome -> allowed.reactions.any {
                ReactionsUtils.compare(it, reaction)
            }
            is TLRPC.TL_chatReactionsAll ->
                reaction is TLRPC.TL_reactionEmoji ||
                    allowed.allow_custom && reaction is TLRPC.TL_reactionCustomEmoji
            else -> false
        }
    }

    private fun alreadyChosen(owner: TLRPC.Message, reaction: TLRPC.Reaction): Boolean =
        owner.reactions?.results?.any { it.chosen && ReactionsUtils.compare(it.reaction, reaction) } == true

    @Synchronized
    private fun markHandled(rule: Rule, messageId: Int) {
        val key = stateKey(rule.account, rule.dialogId)
        if (messageId > statePreferences.getInt(key, 0)) {
            statePreferences.edit().putInt(key, messageId).apply()
        }
    }

    private fun lastHandledId(account: Int, dialogId: Long): Int =
        statePreferences.getInt(stateKey(account, dialogId), 0)

    private fun stateKey(account: Int, dialogId: Long) = "$account:$dialogId"

    private fun loadRules(): List<Rule> {
        val raw = InuConfig.AUTO_REACTIONS.value
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val account = obj.optInt("a", -1)
                val dialogId = obj.optLong("c", 0L)
                val documentId = obj.optLong("d", 0L)
                val emoji = obj.optString("e").takeIf { it.isNotEmpty() }
                if (account < 0 || dialogId >= 0 || documentId == 0L && emoji == null) null
                else Rule(account, dialogId, emoji, documentId)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRules(rules: List<Rule>) {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(JSONObject().apply {
                put("a", rule.account)
                put("c", rule.dialogId)
                if (rule.documentId != 0L) put("d", rule.documentId) else put("e", rule.emoji)
            })
        }
        InuConfig.AUTO_REACTIONS.value = array.toString()
    }
}
