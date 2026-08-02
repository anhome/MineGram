package desu.mintgram.helpers.channels

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import desu.mintgram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.NotificationsController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.chat.layouts.ChatActivityChannelButtonsLayout
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * A local-only watch list for public broadcast channels.
 *
 * No join/import-chatlist request is ever sent. Telegram still receives ordinary getHistory
 * requests, so this feature must not be presented as anonymous network access.
 */
object DeadChannelHelper {
    private const val PREFS = "mintgram_dead_channels"
    private const val SYNC_INTERVAL_MS = 5L * 60L * 1000L
    private const val INITIAL_SYNC_DELAY_MS = 2_000L
    private const val HISTORY_LIMIT = 100

    private data class Controls(
        val root: LinearLayout,
        val joinButton: TextView,
        val divider: View,
        val deadButton: TextView,
        var stockVisibility: Int = View.VISIBLE,
    )

    private val controls = WeakHashMap<ChatActivity, Controls>()
    private val syntheticDialogs = Array(UserConfig.MAX_ACCOUNT_COUNT) {
        ConcurrentHashMap.newKeySet<Long>()
    }
    private val scheduledAccounts = ConcurrentHashMap.newKeySet<Int>()
    private val syncing = ConcurrentHashMap.newKeySet<String>()

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0)
    }

    @JvmStatic
    fun onControllerCreated(controller: MessagesController, account: Int) {
        if (!InuConfig.DEAD_CHANNELS.value || !UserConfig.getInstance(account).isClientActivated) return
        hydrate(account, controller)
        schedule(account, INITIAL_SYNC_DELAY_MS)
    }

    @JvmStatic
    fun onFeatureChanged(enabled: Boolean) {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            val controller = MessagesController.getInstance(account)
            if (enabled) {
                hydrate(account, controller)
                schedule(account, 0)
            } else {
                removeSyntheticDialogs(account, controller)
            }
        }
    }

    @JvmStatic
    fun isWatched(account: Int, dialogId: Long): Boolean {
        return InuConfig.DEAD_CHANNELS.value && watchedIds(account).contains(dialogId)
    }

    @JvmStatic
    fun injectDialogs(controller: MessagesController, account: Int) {
        if (!InuConfig.DEAD_CHANNELS.value) return
        for (dialogId in watchedIds(account)) {
            val chat = controller.getChat(-dialogId) ?: continue
            if (!isEligible(chat)) continue
            ensureDialog(controller, account, dialogId)
        }
    }

    @JvmStatic
    fun attachButton(
        activity: ChatActivity,
        buttonsLayout: ChatActivityChannelButtonsLayout,
        stockButton: View,
    ) {
        if (controls.containsKey(activity)) return
        val context = activity.context ?: return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0f
        }
        val join = createTextButton(activity, LocaleController.getString(R.string.ProfileJoinChannel)).apply {
            setOnClickListener { stockButton.performClick() }
        }
        val divider = View(context).apply {
            setBackgroundColor(activity.getThemedColor(org.telegram.ui.ActionBar.Theme.key_divider))
        }
        val dead = createTextButton(activity, LocaleController.getString(R.string.InuDeadChannelButton)).apply {
            setOnClickListener { toggleFromChat(activity) }
        }
        root.addView(join, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(divider, LinearLayout.LayoutParams(AndroidUtilities.dp(1f), AndroidUtilities.dp(22f)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(dead, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f))
        buttonsLayout.container.addView(
            root,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER),
        )
        controls[activity] = Controls(root, join, divider, dead)
    }

    @JvmStatic
    fun updateButton(
        activity: ChatActivity,
        stockButton: View,
        animated: Boolean,
    ) {
        val controls = controls[activity] ?: return
        val chat = activity.currentChat
        val show = InuConfig.DEAD_CHANNELS.value && chat != null && isEligible(chat)
        val watched = show && isWatched(activity.currentAccount, -chat!!.id)
        controls.deadButton.text = LocaleController.getString(
            if (watched) {
                R.string.InuDeadChannelRemoveButton
            } else {
                R.string.InuDeadChannelButton
            },
        )
        if (show) {
            if (controls.root.visibility != View.VISIBLE) {
                controls.stockVisibility = stockButton.visibility
            }
            stockButton.visibility = View.INVISIBLE
            controls.root.visibility = View.VISIBLE
            controls.root.animate().cancel()
            if (animated) {
                controls.root.alpha = 0f
                controls.root.scaleX = 0.96f
                controls.root.animate().alpha(1f).scaleX(1f).setDuration(220L).start()
            } else {
                controls.root.alpha = 1f
                controls.root.scaleX = 1f
            }
            // Once locally followed, replace the Join/Dead pair with one full-width Leave button.
            val deadParams = controls.deadButton.layoutParams as? LinearLayout.LayoutParams
            if (watched) {
                controls.joinButton.visibility = View.GONE
                controls.divider.visibility = View.GONE
                deadParams?.let {
                    it.width = LinearLayout.LayoutParams.MATCH_PARENT
                    it.weight = 0f
                    controls.deadButton.layoutParams = it
                }
            } else {
                controls.joinButton.visibility = View.VISIBLE
                controls.divider.visibility = View.VISIBLE
                deadParams?.let {
                    it.width = 0
                    it.weight = 1f
                    controls.deadButton.layoutParams = it
                }
            }
        } else {
            controls.root.animate().cancel()
            if (controls.root.visibility == View.VISIBLE) {
                stockButton.visibility = controls.stockVisibility
            }
            controls.root.visibility = View.GONE
        }
    }

    @JvmStatic
    fun onChatOpened(activity: ChatActivity) {
        val dialogId = activity.dialogId
        val account = activity.currentAccount
        if (!isWatched(account, dialogId)) return
        val controller = MessagesController.getInstance(account)
        controller.dialogs_dict[dialogId]?.let {
            it.unread_count = 0
            it.unread_mark = false
        }
        NotificationsController.getInstance(account).removeNotificationsForDialog(dialogId)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        syncChannel(account, dialogId)
    }

    @JvmStatic
    fun appendSearchResults(
        account: Int,
        query: String,
        results: ArrayList<Any>,
        names: ArrayList<CharSequence>,
    ) {
        if (!InuConfig.DEAD_CHANNELS.value) return
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return
        for (dialogId in watchedIds(account)) {
            val chat = MessagesStorage.getInstance(account).getChatSync(-dialogId) ?: continue
            val username = ChatObject.getPublicUsername(chat).orEmpty()
            if (!chat.title.lowercase().contains(normalized) &&
                !username.lowercase().contains(normalized)
            ) continue
            if (results.any { it is TLRPC.Chat && it.id == chat.id }) continue
            results.add(chat)
            names.add(chat.title)
        }
    }

    private fun toggleFromChat(activity: ChatActivity) {
        val chat = activity.currentChat ?: return
        val account = activity.currentAccount
        val dialogId = -chat.id
        if (isWatched(account, dialogId)) {
            remove(account, dialogId)
            updateButton(activity, activity.bottomOverlayChatText, true)
            BulletinFactory.of(activity)
                .createSimpleBulletin(
                    R.raw.contact_check,
                    LocaleController.getString(R.string.InuDeadChannelRemoved),
                ).show()
            return
        }
        val parent = activity.parentActivity ?: return
        AlertDialog.Builder(parent, activity.resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuDeadChannelConfirmTitle))
            .setMessage(LocaleController.getString(R.string.InuDeadChannelConfirmText))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                add(account, chat)
                updateButton(activity, activity.bottomOverlayChatText, true)
                BulletinFactory.of(activity)
                    .createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.getString(R.string.InuDeadChannelAdded),
                    ).show()
            }.show()
    }

    private fun add(account: Int, chat: TLRPC.Chat) {
        val dialogId = -chat.id
        updateWatched(account) { it.add(dialogId) }
        MessagesController.getInstance(account).putChat(chat, false)
        MessagesStorage.getInstance(account).putUsersAndChats(
            emptyList(),
            Collections.singletonList(chat),
            true,
            true,
        )
        val controller = MessagesController.getInstance(account)
        ensureDialog(controller, account, dialogId)
        controller.sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
        syncChannel(account, dialogId)
        schedule(account, SYNC_INTERVAL_MS)
    }

    private fun remove(account: Int, dialogId: Long) {
        updateWatched(account) { it.remove(dialogId) }
        prefs.edit {
            remove(lastIdKey(account, dialogId))
        }
        val controller = MessagesController.getInstance(account)
        if (syntheticDialogs[account].remove(dialogId)) {
            controller.dialogs_dict[dialogId]?.let { dialog ->
                controller.dialogs_dict.remove(dialogId)
                controller.getAllDialogs().remove(dialog)
                controller.dialogMessage.remove(dialogId)
            }
            controller.sortDialogs(null)
        }
        NotificationsController.getInstance(account).removeNotificationsForDialog(dialogId)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    private fun hydrate(account: Int, controller: MessagesController) {
        val ids = watchedIds(account)
        if (ids.isEmpty()) return
        MessagesStorage.getInstance(account).storageQueue.postRunnable {
            val chats = ids.mapNotNull { MessagesStorage.getInstance(account).getChatSync(-it) }
            AndroidUtilities.runOnUIThread {
                for (chat in chats) {
                    controller.putChat(chat, true)
                    if (isEligible(chat)) ensureDialog(controller, account, -chat.id)
                }
                controller.sortDialogs(null)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
            }
        }
    }

    private fun ensureDialog(
        controller: MessagesController,
        account: Int,
        dialogId: Long,
    ): TLRPC.Dialog {
        controller.dialogs_dict[dialogId]?.let { return it }
        val dialog = TLRPC.TL_dialog().apply {
            id = dialogId
            flags = 1
            peer = TLRPC.TL_peerChannel().also { it.channel_id = -dialogId }
            notify_settings = TLRPC.TL_peerNotifySettings()
        }
        controller.dialogs_dict.put(dialogId, dialog)
        controller.getAllDialogs().add(dialog)
        syntheticDialogs[account].add(dialogId)
        return dialog
    }

    private fun removeSyntheticDialogs(account: Int, controller: MessagesController) {
        for (dialogId in syntheticDialogs[account].toList()) {
            controller.dialogs_dict[dialogId]?.let {
                controller.dialogs_dict.remove(dialogId)
                controller.getAllDialogs().remove(it)
                controller.dialogMessage.remove(dialogId)
            }
        }
        syntheticDialogs[account].clear()
        controller.sortDialogs(null)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
    }

    private fun schedule(account: Int, delay: Long) {
        if (!InuConfig.DEAD_CHANNELS.value || !scheduledAccounts.add(account)) return
        Utilities.globalQueue.postRunnable({
            scheduledAccounts.remove(account)
            if (!InuConfig.DEAD_CHANNELS.value ||
                !UserConfig.getInstance(account).isClientActivated
            ) return@postRunnable
            syncAll(account)
            schedule(account, SYNC_INTERVAL_MS)
        }, delay)
    }

    private fun syncAll(account: Int) {
        for (dialogId in watchedIds(account)) syncChannel(account, dialogId)
    }

    private fun syncChannel(account: Int, dialogId: Long) {
        if (!isWatched(account, dialogId)) return
        val key = account.toString() + ":" + dialogId
        if (!syncing.add(key)) return
        val controller = MessagesController.getInstance(account)
        var chat = controller.getChat(-dialogId)
        if (chat == null) {
            syncing.remove(key)
            MessagesStorage.getInstance(account).storageQueue.postRunnable {
                val storedChat = MessagesStorage.getInstance(account).getChatSync(-dialogId)
                AndroidUtilities.runOnUIThread {
                    if (storedChat != null && isWatched(account, dialogId)) {
                        controller.putChat(storedChat, true)
                        ensureDialog(controller, account, dialogId)
                        syncChannel(account, dialogId)
                    }
                }
            }
            return
        }
        if (!ChatObject.isNotInChat(chat)) {
            updateWatched(account) { it.remove(dialogId) }
            syntheticDialogs[account].remove(dialogId)
            syncing.remove(key)
            return
        }
        if (!isEligible(chat)) {
            syncing.remove(key)
            return
        }
        val request = TLRPC.TL_messages_getHistory().apply {
            peer = controller.getInputPeer(dialogId)
            limit = HISTORY_LIMIT
            min_id = lastId(account, dialogId)
        }
        ConnectionsManager.getInstance(account).sendRequest(request) { response, error ->
            syncing.remove(key)
            if (error != null || response !is TLRPC.messages_Messages) return@sendRequest
            AndroidUtilities.runOnUIThread {
                if (!isWatched(account, dialogId)) return@runOnUIThread
                controller.putUsers(response.users, false)
                controller.putChats(response.chats, false)
                MessagesStorage.getInstance(account).putUsersAndChats(
                    response.users,
                    response.chats,
                    true,
                    true,
                )
                ensureDialog(controller, account, dialogId)
                val previousId = lastId(account, dialogId)
                val latestId = response.messages.maxOfOrNull { it.id } ?: previousId
                if (latestId <= previousId) return@runOnUIThread

                val users = LongSparseArray<TLRPC.User>()
                val chats = LongSparseArray<TLRPC.Chat>()
                response.users.forEach { users.put(it.id, it) }
                response.chats.forEach { chats.put(it.id, it) }
                val fresh = response.messages
                    .filter { previousId != 0 && it.id > previousId }
                    .sortedBy { it.id }
                val messagesToApply = if (previousId == 0) {
                    response.messages.maxByOrNull { it.id }?.let { listOf(it) }.orEmpty()
                } else {
                    fresh
                }
                saveLastId(account, dialogId, latestId)
                if (messagesToApply.isEmpty()) {
                    controller.sortDialogs(null)
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
                    return@runOnUIThread
                }

                val active = (org.telegram.ui.LaunchActivity.getSafeLastFragment() as? ChatActivity)
                    ?.let { it.currentAccount == account && it.dialogId == dialogId } == true
                messagesToApply.forEach {
                    it.dialog_id = dialogId
                    it.unread = previousId != 0 && !active
                    it.media_unread = false
                }
                MessagesStorage.getInstance(account).putMessages(
                    ArrayList(messagesToApply),
                    true,
                    true,
                    false,
                    0,
                    0,
                    0,
                )
                val objects = ArrayList(messagesToApply.map {
                    MessageObject(account, it, users, chats, true, false)
                })
                controller.updateInterfaceWithMessages(dialogId, objects, 0)
                controller.dialogs_dict[dialogId]?.let {
                    if (active) {
                        it.unread_count = 0
                        it.unread_mark = false
                    } else if (previousId != 0) {
                        it.unread_count += messagesToApply.size
                    }
                }
                controller.sortDialogs(null)
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload)
            }
        }
    }

    private fun isEligible(chat: TLRPC.Chat): Boolean {
        return ChatObject.isChannelAndNotMegaGroup(chat) &&
            ChatObject.isNotInChat(chat) &&
            chat !is TLRPC.TL_channelForbidden &&
            !TextUtils.isEmpty(ChatObject.getPublicUsername(chat))
    }

    private fun createTextButton(activity: ChatActivity, text: CharSequence): TextView {
        return TextView(activity.context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getThemedColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText))
            setPadding(AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f), 0)
            ScaleStateListAnimator.apply(this, 0.04f, 1.2f)
        }
    }

    @Synchronized
    private fun watchedIds(account: Int): Set<Long> {
        return prefs.getStringSet("account_" + account, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    @Synchronized
    private fun updateWatched(account: Int, block: (MutableSet<Long>) -> Unit) {
        val ids = watchedIds(account).toMutableSet()
        block(ids)
        prefs.edit {
            putStringSet("account_" + account, ids.mapTo(mutableSetOf()) { it.toString() })
        }
    }

    private fun lastId(account: Int, dialogId: Long): Int =
        prefs.getInt(lastIdKey(account, dialogId), 0)

    private fun saveLastId(account: Int, dialogId: Long, id: Int) {
        prefs.edit { putInt(lastIdKey(account, dialogId), max(id, lastId(account, dialogId))) }
    }

    private fun lastIdKey(account: Int, dialogId: Long) =
        "last_" + account + "_" + dialogId
}
