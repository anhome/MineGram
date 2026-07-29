package desu.mintgram.ui.settings.icons

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import desu.mintgram.InuConfig
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.icons.CustomIconPack
import desu.mintgram.helpers.icons.IconPackStorage
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Built-in (Off/Solar) + user-created icon packs — tap selects & applies, long-press on a custom
 * pack offers rename/delete. Opening a custom pack's editor is a separate explicit action (pencil-
 * style row appended after selecting it), not bundled into the select tap. */
class IconPacksActivity : SettingsPageActivity() {
    private var customPacks = IconPackStorage.listPacks()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIconPacks)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconPacksInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIconPacksBuiltIn)))
        items.add(
            UItem.asRadio(BUTTON_OFF, LocaleController.getString(R.string.InuIconReplacementOff))
                .setChecked(InuConfig.ICON_REPLACEMENT.value == InuConfig.IconReplacementItem.OFF)
        )
        items.add(
            UItem.asRadio(BUTTON_SOLAR, LocaleController.getString(R.string.InuIconReplacementSolar))
                .setChecked(InuConfig.ICON_REPLACEMENT.value == InuConfig.IconReplacementItem.SOLAR)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIconPacksCustom)))
        val activeCustomId = InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value
        val isCustomActive = InuConfig.ICON_REPLACEMENT.value == InuConfig.IconReplacementItem.CUSTOM
        for ((i, pack) in customPacks.withIndex()) {
            val subtitle = LocaleController.formatPluralString("InuIconPacksCount", pack.overriddenNames.size)
            items.add(
                UItem.asRadio(ITEM_BASE + i, pack.name, subtitle)
                    .setChecked(isCustomActive && pack.id == activeCustomId)
            )
        }
        if (customPacks.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconPacksNoCustom)))
        } else {
            items.add(UItem.asShadow(null))
        }
        items.add(UItem.asButton(BUTTON_CREATE, R.drawable.msg_add, LocaleController.getString(R.string.InuIconPacksCreate)))
        items.add(UItem.asButton(BUTTON_IMPORT, R.drawable.msg_download, LocaleController.getString(R.string.InuIconPacksImport)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconPacksImportInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_OFF -> selectBuiltIn(InuConfig.IconReplacementItem.OFF)
            BUTTON_SOLAR -> selectBuiltIn(InuConfig.IconReplacementItem.SOLAR)
            BUTTON_CREATE -> showCreateDialog()
            BUTTON_IMPORT -> launchImportPicker()
            else -> {
                val idx = item.id - ITEM_BASE
                if (idx in customPacks.indices) {
                    val pack = customPacks[idx]
                    InuConfig.ICON_REPLACEMENT.value = InuConfig.IconReplacementItem.CUSTOM
                    InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value = pack.id
                    listView.adapter.update(true)
                    showRestartBulletin()
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val idx = item.id - ITEM_BASE
        if (idx !in customPacks.indices) return false
        val pack = customPacks[idx]
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_photos, LocaleController.getString(R.string.InuIconPacksEditIcons)) {
                presentFragment(IconPackEditorActivity(pack.id))
            }
            .add(R.drawable.msg_edit, LocaleController.getString(R.string.InuIconPacksRename)) {
                showRenameDialog(pack)
            }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                IconPackStorage.deletePack(pack.id)
                customPacks = IconPackStorage.listPacks()
                listView.adapter.update(true)
            }
            .show()
        return true
    }

    private fun selectBuiltIn(mode: Int) {
        InuConfig.ICON_REPLACEMENT.value = mode
        InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value = ""
        listView.adapter.update(true)
        showRestartBulletin()
    }

    private fun showCreateDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val nameInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            hint = LocaleController.getString(R.string.InuIconPacksNameHint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2))

        showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.InuIconPacksCreate))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                    val name = nameInput.text.toString().trim().ifEmpty { LocaleController.getString(R.string.InuIconPacksDefaultName) }
                    val pack = IconPackStorage.createPack(name)
                    customPacks = IconPackStorage.listPacks()
                    listView.adapter.update(true)
                    presentFragment(IconPackEditorActivity(pack.id))
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
    }

    private fun launchImportPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT_PACK)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_IMPORT_PACK) return
        val uri = data?.data ?: return
        val ctx = context ?: return
        Utilities.globalQueue.postRunnable {
            val imported = try {
                ctx.contentResolver.openInputStream(uri)?.use { IconPackStorage.importPackFromZip(it) }
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            AndroidUtilities.runOnUIThread {
                customPacks = IconPackStorage.listPacks()
                listView.adapter.update(true)
                if (imported != null) {
                    BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.import_check,
                        LocaleController.formatString(R.string.InuIconPacksImportSuccess, imported.name)
                    ).show()
                } else {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.getString(R.string.InuIconPacksImportError)
                    ).show()
                }
            }
        }
    }

    private fun showRenameDialog(pack: CustomIconPack) {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val nameInput = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setText(pack.name)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2))

        showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.InuIconPacksRename))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        IconPackStorage.renamePack(pack.id, name)
                        customPacks = IconPackStorage.listPacks()
                        listView.adapter.update(true)
                    }
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create()
        )
    }

    companion object {
        private val BUTTON_OFF = InuUtils.generateId()
        private val BUTTON_SOLAR = InuUtils.generateId()
        private val BUTTON_CREATE = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()
        private const val ITEM_BASE = 24_000
        private const val REQ_IMPORT_PACK = 41002

        private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "icon-packs",
            titleRes = R.string.InuIconPacks,
            iconRes = R.drawable.msg_photos,
            factory = ::IconPacksActivity,
        )
    }
}
