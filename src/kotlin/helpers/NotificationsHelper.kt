package desu.mintgram.helpers

import android.content.Context
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import desu.mintgram.InuConfig
import desu.mintgram.helpers.chat.BlockedMessagesHelper
import desu.mintgram.helpers.security.ParanoiaHelper
import desu.mintgram.helpers.security.PasscodeHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R

object NotificationsHelper {
    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("mintgram_notifications", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun smallIconRes(): Int = when (InuConfig.NOTIFICATION_ICON.value) {
        InuConfig.NotificationIconItem.TELEGRAM -> R.drawable.notification
        else -> R.drawable.icon_notification_inu
    }

    @JvmStatic
    fun shouldSuppressNotifications(account: Int): Boolean =
        PasscodeHelper.isAccountHidden(account) || ParanoiaHelper.shouldSuppressNotifications()

    @JvmStatic
    fun shouldSuppressMessageNotification(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        return BlockedMessagesHelper.shouldHide(messageObject)
            || ParanoiaHelper.isHidden(messageObject.currentAccount, messageObject.dialogId)
    }

    // Stock's `NotificationsController.wearNotificationsIds` (dialogId -> notification id) is the only record of
    // what is on screen, and every cancel path diffs against it — but it is in-memory only, while posted
    // notifications outlive the process. Mirroring it to disk is what makes those cancel paths survive a restart.
    private fun getWearIdsKey(account: Int) = "wear_ids_$account"

    @JvmStatic
    fun loadWearNotificationIds(account: Int, into: LongSparseArray<Int>) {
        into.clear()
        val stored = prefs.getString(getWearIdsKey(account), null) ?: return
        for (entry in stored.splitToSequence(',')) {
            val separator = entry.indexOf(':')
            if (separator <= 0) continue
            val dialogId = entry.substring(0, separator).toLongOrNull() ?: continue
            val notificationId = entry.substring(separator + 1).toIntOrNull() ?: continue
            into.put(dialogId, notificationId)
        }
    }

    @JvmStatic
    fun saveWearNotificationIds(account: Int, ids: LongSparseArray<Int>) {
        val key = getWearIdsKey(account)
        val stored = (0 until ids.size()).joinToString(",") { "${ids.keyAt(it)}:${ids.valueAt(it)}" }
        if (prefs.getString(key, "") == stored) return
        // commit: this races a process death that may come right after posting the notifications
        prefs.edit(commit = true) {
            if (stored.isEmpty()) remove(key) else putString(key, stored)
        }
    }
}
