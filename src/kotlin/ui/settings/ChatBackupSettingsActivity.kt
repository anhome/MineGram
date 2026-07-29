package desu.mintgram.ui.settings

import android.os.Bundle
import android.view.View
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.chat.ChatBackupHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.DialogsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatBackupSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatBackup)

    private var backups: List<ChatBackupHelper.BackupRecord> = emptyList()
    private val dateFormat by lazy { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(mkSubPageButton(BUTTON_MAKE_BACKUP, LocaleController.getString(R.string.InuChatBackupMake)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatBackupMakeInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatBackupSaved)))
        backups = ChatBackupHelper.getBackups()
        if (backups.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuChatBackupNoneSaved)))
        } else {
            backups.forEachIndexed { index, record ->
                items.add(
                    UItem.asButton(
                        BACKUP_ROW_BASE + index,
                        record.dialogName.ifEmpty { LocaleController.getString(R.string.InuChatBackupUnknownChat) },
                        dateFormat.format(Date(record.timestamp))
                    )
                )
            }
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val id = item.id
        if (id >= BACKUP_ROW_BASE && id < BACKUP_ROW_BASE + backups.size) {
            presentFragment(ChatBackupViewerActivity(backups[id - BACKUP_ROW_BASE]))
            return
        }
        if (id == BUTTON_MAKE_BACKUP) openChatPicker()
    }

    private fun openChatPicker() {
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
            putBoolean("canSelectTopics", false)
        }
        val picker = DialogsActivity(args)
        picker.setDelegate { fragment, dids, _, _, _, _, _, _ ->
            fragment.finishFragment()
            val account = UserConfig.selectedAccount
            for (key in dids) {
                ChatBackupHelper.backupChat(account, key.dialogId) { record ->
                    if (record == null) {
                        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuChatBackupFailed)).show()
                    } else {
                        BulletinFactory.of(this)
                            .createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.InuChatBackupSavedTo), record.displayPath)
                            .show()
                        listView?.adapter?.update(true)
                    }
                }
            }
            true
        }
        presentFragment(picker)
    }

    companion object {
        private val BUTTON_MAKE_BACKUP = InuUtils.generateId()
        private const val BACKUP_ROW_BASE = 30_000
    }
}
