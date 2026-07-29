package desu.mintgram.helpers.icons

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Handles a `.icon`/`.icons` file opened from outside the app — Files app, "Open with", a browser
 * download, or tapping it as a chat attachment — same one-tap flow the plugin engine uses (see the
 * VIEW intent-filter in AndroidManifest.xml and [desu.mintgram.InuHooks.handleIntent]). Copies the
 * `content://` URI to a real file, peeks its declared name/icon count for a confirm prompt, then
 * imports through the same [IconPackStorage.importPackFromZip] the manual Settings picker uses.
 */
object IconPackImportHelper {
    fun handle(fragment: BaseFragment, uri: Uri) {
        val ctx = fragment.parentActivity ?: return
        Utilities.globalQueue.postRunnable {
            val file = copyToCache(ctx, uri)
            val info = file?.let { f -> f.inputStream().use { IconPackStorage.peekPackInfo(it) } }
            AndroidUtilities.runOnUIThread {
                if (file == null || info == null) {
                    file?.delete()
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.getString(R.string.InuIconPacksImportError)
                    ).show()
                } else {
                    confirmAndImport(fragment, file, info.first, info.second)
                }
            }
        }
    }

    private fun confirmAndImport(fragment: BaseFragment, file: File, packName: String, iconCount: Int) {
        val ctx = fragment.parentActivity ?: return
        val dialog = AlertDialog.Builder(ctx, fragment.resourceProvider)
            .setTitle(packName)
            .setMessage(LocaleController.formatString(R.string.InuIconPacksImportConfirm, iconCount))
            .setPositiveButton(LocaleController.getString(R.string.InuIconPacksImport)) { _, _ ->
                Utilities.globalQueue.postRunnable {
                    val imported = try {
                        file.inputStream().use { IconPackStorage.importPackFromZip(it) }
                    } catch (e: Exception) {
                        FileLog.e(e)
                        null
                    }
                    file.delete()
                    AndroidUtilities.runOnUIThread {
                        if (imported != null) {
                            BulletinFactory.of(fragment).createSimpleBulletin(
                                R.raw.import_check,
                                LocaleController.formatString(R.string.InuIconPacksImportSuccess, imported.name)
                            ).show()
                        } else {
                            BulletinFactory.of(fragment).createErrorBulletin(
                                LocaleController.getString(R.string.InuIconPacksImportError)
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ -> file.delete() }
            .setOnCancelListener { file.delete() }
            .create()
        fragment.showDialog(dialog)
    }

    private fun copyToCache(ctx: Context, uri: Uri): File? {
        val name = queryDisplayName(ctx, uri) ?: "icon_pack_${System.currentTimeMillis()}.icon"
        val dest = File(AndroidUtilities.getCacheDir(), name)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            dest
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme != "content") return null
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
