package desu.mintgram.ui.settings.channels

import android.os.Bundle
import android.view.View
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.channels.AutoReactionHelper
import desu.mintgram.ui.settings.RadioDialogBuilder
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.DialogsActivity

class AutoReactionsActivity :
    SettingsPageActivity(),
    NotificationCenter.NotificationCenterDelegate {

    private var rules = emptyList<AutoReactionHelper.Rule>()
    private var pendingDialogId = 0L

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAutoReactions)

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate()) return false
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.chatInfoDidLoad)
        return true
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.chatInfoDidLoad)
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_ADD,
                R.drawable.msg_add,
                LocaleController.getString(R.string.InuAutoReactionsAddChannel),
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAutoReactionsInfo)))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAutoReactionsConfigured)))

        rules = AutoReactionHelper.rules(currentAccount)
        if (rules.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuAutoReactionsEmpty)))
            return
        }

        rules.forEachIndexed { index, rule ->
            val title = messagesController.getChat(-rule.dialogId)?.title
                ?: rule.dialogId.toString()
            items.add(
                UItem.asButton(
                    RULE_BASE + index,
                    title,
                    rule.visibleReaction().toCharSequence(20),
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) {
            openChannelPicker()
            return
        }
        val index = item.id - RULE_BASE
        if (index in rules.indices) selectChannel(rules[index].dialogId)
    }

    private fun openChannelPicker() {
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putBoolean("checkCanWrite", false)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY)
            putBoolean("canSelectTopics", false)
        }
        val picker = DialogsActivity(args)
        picker.setDelegate { fragment, dids, _, _, _, _, _, _ ->
            val dialogId = dids.firstOrNull()?.dialogId ?: return@setDelegate false
            val chat = messagesController.getChat(-dialogId)
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat)) return@setDelegate false
            fragment.finishFragment()
            selectChannel(dialogId)
            true
        }
        presentFragment(picker)
    }

    private fun selectChannel(dialogId: Long) {
        if (messagesController.getChatFull(-dialogId) == null) {
            pendingDialogId = dialogId
            messagesController.loadFullChat(-dialogId, classGuid, true)
            BulletinFactory.of(this)
                .createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.InuAutoReactionsLoading),
                )
                .show()
            return
        }
        showReactionPicker(dialogId)
    }

    private fun showReactionPicker(dialogId: Long) {
        val choices = AutoReactionHelper.availableReactions(currentAccount, dialogId)
        if (choices.isEmpty()) {
            BulletinFactory.of(this)
                .createErrorBulletin(LocaleController.getString(R.string.InuAutoReactionsUnavailable))
                .show()
            return
        }
        val current = AutoReactionHelper.rule(currentAccount, dialogId)
        val items = ArrayList<RadioDialogBuilder.Item>()
        items.add(RadioDialogBuilder.Item(LocaleController.getString(R.string.InuAutoReactionsDisable)))
        choices.forEach { choice ->
            items.add(RadioDialogBuilder.Item(choice.visibleReaction().toCharSequence(22)))
        }
        val selected = choices.indexOfFirst {
            it.emoji == current?.emoji && it.documentId == current?.documentId
        }.let { if (it < 0) 0 else it + 1 }

        val ctx = context ?: return
        showDialog(
            RadioDialogBuilder(ctx, resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuAutoReactionsSelectReaction))
                .setItems(items, selected) { _, which ->
                    if (which == 0) {
                        AutoReactionHelper.removeRule(currentAccount, dialogId)
                    } else {
                        AutoReactionHelper.saveRule(
                            choices[which - 1].toRule(currentAccount, dialogId)
                        )
                    }
                    listView.adapter.update(true)
                }
                .create()
        )
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id != NotificationCenter.chatInfoDidLoad || account != currentAccount) return
        val full = args.firstOrNull() as? TLRPC.ChatFull ?: return
        if (pendingDialogId != 0L && full.id == -pendingDialogId) {
            val dialogId = pendingDialogId
            pendingDialogId = 0L
            showReactionPicker(dialogId)
        }
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val RULE_BASE = 52_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "auto-reactions",
            titleRes = R.string.InuAutoReactions,
            iconRes = R.drawable.msg_reactions,
            factory = ::AutoReactionsActivity,
        )
    }
}
