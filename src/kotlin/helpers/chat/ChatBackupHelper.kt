package desu.mintgram.helpers.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import desu.mintgram.helpers.security.MessageHistoryHelper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch

// Plain-text export of everything locally known about a chat: whatever's already cached in
// messages_v2 (Telegram's own local history cache - "quick" means local-only, no re-fetching
// full history from the server) plus anything MessageHistoryHelper captured into
// inu_deleted_messages, so a message the other side deleted still shows up, marked as such.
// Saved to the public Downloads/Mintgram folder (MediaStore on API 29+, direct file below that)
// and recorded locally so ChatBackupSettingsActivity can list + reopen past backups.
object ChatBackupHelper {
    private data class Entry(val id: Int, val date: Int, val senderId: Long, val text: String, val deleted: Boolean)

    data class BackupRecord(
        val dialogName: String,
        val displayPath: String,
        val location: String,
        val isContentUri: Boolean,
        val timestamp: Long,
    )

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("mintgram_chat_backups", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun getBackups(): List<BackupRecord> {
        val json = prefs.getString("records", "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BackupRecord(
                    dialogName = o.getString("name"),
                    displayPath = o.getString("displayPath"),
                    location = o.getString("location"),
                    isContentUri = o.getBoolean("isContentUri"),
                    timestamp = o.getLong("timestamp"),
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun addBackupRecord(record: BackupRecord) {
        val arr = JSONArray()
        for (r in getBackups() + record) {
            arr.put(JSONObject().apply {
                put("name", r.dialogName)
                put("displayPath", r.displayPath)
                put("location", r.location)
                put("isContentUri", r.isContentUri)
                put("timestamp", r.timestamp)
            })
        }
        prefs.edit { putString("records", arr.toString()) }
    }

    @JvmStatic
    fun readBackupContent(record: BackupRecord): String = runCatching {
        if (record.isContentUri) {
            ApplicationLoader.applicationContext.contentResolver.openInputStream(Uri.parse(record.location))
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } else {
            File(record.location).readText()
        }
    }.getOrDefault("")

    // account/dialogId only - callers may not have a live ChatActivity (e.g. picked from Settings).
    @JvmStatic
    fun backupChat(account: Int, dialogId: Long, onResult: (BackupRecord?) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val dialogName = DialogObject.getName(account, dialogId)
            val entries = collectEntries(account, dialogId)
            val text = format(account, dialogName, entries)
            val record = writeBackup(dialogName, dialogId, text)
            if (record != null) addBackupRecord(record)
            AndroidUtilities.runOnUIThread { onResult(record) }
        }
    }

    private fun collectEntries(account: Int, dialogId: Long): List<Entry> {
        val storage = MessagesStorage.getInstance(account)
        val latch = CountDownLatch(1)
        val result = ArrayList<Entry>()
        storage.storageQueue.postRunnable {
            try {
                val liveIds = HashSet<Int>()
                val cursor = storage.database.queryFinalized(
                    "SELECT data, mid, date FROM messages_v2 WHERE uid = ? ORDER BY date ASC",
                    dialogId,
                )
                try {
                    while (cursor.next()) {
                        val buffer = cursor.byteBufferValue(0) ?: continue
                        try {
                            val message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false)
                            val mid = cursor.intValue(1)
                            liveIds.add(mid)
                            result.add(Entry(mid, cursor.intValue(2), MessageObject.getFromChatId(message), message.message ?: "", false))
                        } finally {
                            buffer.reuse()
                        }
                    }
                } finally {
                    cursor.dispose()
                }

                for (deleted in MessageHistoryHelper.getDeletedMessages(account, dialogId, limit = Int.MAX_VALUE)) {
                    if (deleted.message.id in liveIds) continue // ghosted - already listed from messages_v2 above
                    result.add(Entry(deleted.message.id, deleted.date, deleted.senderId, deleted.message.message ?: "", true))
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return result.sortedBy { it.date }
    }

    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    private fun format(account: Int, dialogName: String, entries: List<Entry>): String {
        val sb = StringBuilder()
        sb.append(dialogName).append('\n')
        sb.append("=".repeat(40)).append('\n')
        for (e in entries) {
            val date = dateFormat.format(Date(e.date.toLong() * 1000))
            val sender = DialogObject.getName(account, e.senderId).ifEmpty { e.senderId.toString() }
            val text = e.text.ifEmpty { "[медиа]" }
            sb.append('[').append(date).append("] ")
            if (e.deleted) sb.append("🗑 ")
            sb.append(sender).append(": ").append(text).append('\n')
        }
        return sb.toString()
    }

    private fun writeBackup(dialogName: String, dialogId: Long, text: String): BackupRecord? {
        val safeName = dialogName.ifBlank { dialogId.toString() }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val filename = "Mintgram_${safeName}_${System.currentTimeMillis()}.txt"
        val displayPath = "${Environment.DIRECTORY_DOWNLOADS}/Mintgram/$filename"
        val context = ApplicationLoader.applicationContext
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Mintgram")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                BackupRecord(dialogName, displayPath, uri.toString(), true, System.currentTimeMillis())
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mintgram").apply { mkdirs() }
                val file = File(dir, filename)
                file.writeText(text)
                BackupRecord(dialogName, displayPath, file.absolutePath, false, System.currentTimeMillis())
            }
        }.getOrNull()
    }
}
