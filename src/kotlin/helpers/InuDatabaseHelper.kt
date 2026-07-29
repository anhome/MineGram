package desu.mintgram.helpers

import com.google.android.exoplayer2.util.Log
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.MessagesStorage

object InuDatabaseHelper {
    @JvmStatic
    fun migrate(messagesStorage: MessagesStorage) {
        val db = messagesStorage.database;

        db.executeFast("CREATE TABLE IF NOT EXISTS inu_kv(key TEXT PRIMARY KEY, value TEXT)")
            .stepThis().dispose();
        var version = readKv(db, "version")?.toInt() ?: 0;
        Log.d("InuDatabaseHelper", "migrating from version $version")

        if (version == 0) {
            db.executeFast("CREATE TABLE IF NOT EXISTS inu_folder_meta(filter_id INTEGER PRIMARY KEY, emoticon TEXT)")
                .stepThis().dispose();
            writeKv(db, "version", "1")
            version = 1
        }

        if (version == 1) {
            db.executeFast(
                "CREATE TABLE IF NOT EXISTS inu_deleted_messages(account INTEGER, dialog_id INTEGER, message_id INTEGER, sender_id INTEGER, date INTEGER, deleted_date INTEGER, data BLOB, PRIMARY KEY(account, dialog_id, message_id))"
            ).stepThis().dispose();
            db.executeFast(
                "CREATE INDEX IF NOT EXISTS inu_deleted_messages_dialog_idx ON inu_deleted_messages(account, dialog_id, deleted_date)"
            ).stepThis().dispose();
            db.executeFast(
                "CREATE TABLE IF NOT EXISTS inu_edited_messages(account INTEGER, dialog_id INTEGER, message_id INTEGER, edit_date INTEGER, captured_date INTEGER, data BLOB)"
            ).stepThis().dispose();
            db.executeFast(
                "CREATE INDEX IF NOT EXISTS inu_edited_messages_msg_idx ON inu_edited_messages(account, dialog_id, message_id, captured_date)"
            ).stepThis().dispose();
            writeKv(db, "version", "2")
            version = 2
        }

        Log.d("InuDatabaseHelper", "migrating finished, new version = $version")
    }

    fun readKv(db: SQLiteDatabase, key: String): String? {
        val cursor = db.queryFinalized("select value from inu_kv where key = ?", key);
        try {
            if (!cursor.next()) {
                return null;
            }
            return cursor.stringValue(0);
        } finally {
            cursor.dispose();
        }
    }

    fun writeKv(db: SQLiteDatabase, key: String, value: String): Unit {
        val query = db.executeFast("INSERT OR REPLACE INTO inu_kv VALUES(?, ?)");
        query.bindString(1, key)
        query.bindString(2, value)
        query.step()
        query.dispose()
    }
}