package desu.mintgram.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import desu.mintgram.helpers.chat.ChatBackupHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ChatBackupViewerActivity(private val record: ChatBackupHelper.BackupRecord) : SettingsPageActivity() {
    override fun getTitle(): CharSequence = record.dialogName.ifEmpty { record.displayPath }

    private var cachedView: View? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = parentActivity ?: return
        val view = cachedView ?: buildContentView(ctx).also { cachedView = it }
        items.add(UItem.asFullscreenCustom(view, AndroidUtilities.navigationBarHeight))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        // read-only viewer, nothing clickable
    }

    private fun buildContentView(ctx: Context): View {
        val text = TextView(ctx).apply {
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(24f))
            text = ChatBackupHelper.readBackupContent(record)
        }
        return ScrollView(ctx).apply { addView(text) }
    }
}
