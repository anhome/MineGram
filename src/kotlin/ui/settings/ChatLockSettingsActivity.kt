package desu.mintgram.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.helpers.security.ChatLockHelper
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.DialogsActivity

class ChatLockSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatLock)

    private var lockedList: List<Long> = emptyList()

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuArchiveLockMethod)))
        items.add(
            UItem.asCheck(RADIO_CODE, LocaleController.getString(R.string.InuArchiveLockMethodCode))
                .setChecked(ChatLockHelper.getMethod() == ChatLockHelper.Method.CODE)
        )
        items.add(
            UItem.asCheck(RADIO_BIOMETRIC, LocaleController.getString(R.string.InuArchiveLockMethodBiometric))
                .setChecked(ChatLockHelper.getMethod() == ChatLockHelper.Method.BIOMETRIC)
        )
        items.add(UItem.asShadow(if (!BiometricHelper.isSupported()) LocaleController.getString(R.string.InuArchiveLockBiometricUnsupported) else null))

        if (ChatLockHelper.getMethod() == ChatLockHelper.Method.CODE) {
            items.add(
                UItem.asButton(
                    BUTTON_SET_CODE,
                    LocaleController.getString(
                        if (ChatLockHelper.hasCode()) R.string.InuArchiveLockChangeCode else R.string.InuArchiveLockSetCode
                    )
                )
            )
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatLockLockedChats)))
        lockedList = ChatLockHelper.getLockedDialogs().sorted()
        if (lockedList.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatLockNoneLocked)))
        } else {
            lockedList.forEachIndexed { index, dialogId ->
                val name = DialogObject.getName(currentAccount, dialogId).ifEmpty { dialogId.toString() }
                items.add(UItem.asButton(CHAT_ROW_BASE + index, name, LocaleController.getString(R.string.InuChatLockTapToUnlock)).red())
            }
            items.add(UItem.asShadow(null))
        }

        items.add(
            mkSubPageButton(BUTTON_ADD_CHATS, LocaleController.getString(R.string.InuChatLockAddChats))
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatLockAddChatsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val id = item.id
        if (id >= CHAT_ROW_BASE && id < CHAT_ROW_BASE + lockedList.size) {
            ChatLockHelper.unlock(lockedList[id - CHAT_ROW_BASE])
            listView?.adapter?.update(true)
            return
        }
        when (id) {
            RADIO_CODE -> {
                ChatLockHelper.setMethod(ChatLockHelper.Method.CODE)
                listView?.adapter?.update(true)
            }

            RADIO_BIOMETRIC -> {
                ChatLockHelper.setMethod(ChatLockHelper.Method.BIOMETRIC)
                listView?.adapter?.update(true)
            }

            BUTTON_SET_CODE -> promptSetCode { listView?.adapter?.update(true) }

            BUTTON_ADD_CHATS -> {
                if (ChatLockHelper.getMethod() == ChatLockHelper.Method.CODE && !ChatLockHelper.hasCode()) {
                    promptSetCode { openChatPicker() }
                } else {
                    openChatPicker()
                }
            }
        }
    }

    private fun openChatPicker() {
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
            putBoolean("canSelectTopics", false)
        }
        val picker = DialogsActivity(args)
        picker.setDelegate { fragment, dids, _, _, _, _, _, _ ->
            ChatLockHelper.lock(dids.map { it.dialogId })
            fragment.finishFragment()
            listView?.adapter?.update(true)
            true
        }
        presentFragment(picker)
    }

    private fun promptSetCode(onSet: () -> Unit) {
        showInputDialog(
            this,
            title = LocaleController.getString(R.string.InuArchiveLockSetCode),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            onSubmit = { code ->
                if (code.isEmpty()) {
                    false
                } else {
                    ChatLockHelper.setCode(code)
                    onSet()
                    true
                }
            }
        )
    }

    companion object {
        private val RADIO_CODE = InuUtils.generateId()
        private val RADIO_BIOMETRIC = InuUtils.generateId()
        private val BUTTON_SET_CODE = InuUtils.generateId()
        private val BUTTON_ADD_CHATS = InuUtils.generateId()
        private const val CHAT_ROW_BASE = 20_000
    }
}
