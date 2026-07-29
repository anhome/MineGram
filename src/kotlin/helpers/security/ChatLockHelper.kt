package desu.mintgram.helpers.security

import android.content.Context
import android.text.InputType
import androidx.core.content.edit
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

// Gates individual chats behind a code word or biometric, independent of ArchiveLockHelper (own
// prefs file, own code/method) so locking a couple of chats doesn't force the whole Archive
// behind the same password. State lives in its own prefs file for the same reason
// ArchiveLockHelper's does - the code hash never lands in InuConfig's cloud sync / export-to-json.
object ChatLockHelper {
    enum class Method { CODE, BIOMETRIC }

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("mintgram_chat_lock", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun getLockedDialogs(): Set<Long> =
        prefs.getStringSet("lockedDialogs", null)?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    private fun setLockedDialogs(ids: Set<Long>) {
        prefs.edit { putStringSet("lockedDialogs", ids.map { it.toString() }.toSet()) }
    }

    @JvmStatic
    fun isLocked(dialogId: Long): Boolean = dialogId in getLockedDialogs()

    @JvmStatic
    fun lock(dialogIds: Collection<Long>) {
        setLockedDialogs(getLockedDialogs() + dialogIds)
    }

    @JvmStatic
    fun unlock(dialogId: Long) {
        setLockedDialogs(getLockedDialogs() - dialogId)
    }

    @JvmStatic
    fun getMethod(): Method =
        if (prefs.getString("method", "code") == "biometric") Method.BIOMETRIC else Method.CODE

    @JvmStatic
    fun setMethod(method: Method) {
        prefs.edit { putString("method", if (method == Method.BIOMETRIC) "biometric" else "code") }
    }

    @JvmStatic
    fun hasCode(): Boolean = prefs.contains("codeHash") && prefs.contains("codeSalt")

    @JvmStatic
    fun setCode(code: String) {
        SecretHash.store(prefs, "codeHash", "codeSalt", code)
    }

    private fun verifyCode(code: String): Boolean =
        SecretHash.verify(prefs, "codeHash", "codeSalt", code)

    // Called from ChatActivity.onResume, mirrors ArchiveLockHelper.checkAccess. Only challenges
    // dialogId if it's in the locked set - blocks nothing by itself, on failure/cancel it just
    // pops the fragment that already (harmlessly) exists, same as a normal back navigation.
    //
    // onLockShown/onLockResolved bracket the actual challenge so the caller can blur the chat for
    // that window - see ArchiveLockHelper for why these only fire around a real challenge (the
    // "fail open" early-returns below never call onLockShown).
    @JvmStatic
    fun checkAccess(fragment: BaseFragment, dialogId: Long, onLockShown: Runnable, onLockResolved: Runnable) {
        if (!isLocked(dialogId)) return
        when (getMethod()) {
            Method.BIOMETRIC -> {
                val context = fragment.context ?: return
                if (!BiometricHelper.isSupported()) return // no way to challenge - fail open rather than lock the user out
                onLockShown.run()
                BiometricHelper.gate(
                    context, true,
                    onSuccess = { onLockResolved.run() },
                    onFailure = { onLockResolved.run(); fragment.finishFragment() },
                )
            }

            Method.CODE -> {
                if (!hasCode()) return
                onLockShown.run()
                var succeeded = false
                val dialog = showInputDialog(
                    fragment,
                    title = LocaleController.getString(R.string.InuChatLockPrompt),
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    onSubmit = { code ->
                        if (code.isNotEmpty() && verifyCode(code)) {
                            succeeded = true
                            onLockResolved.run()
                            true
                        } else {
                            false
                        }
                    },
                )
                dialog?.setOnDismissListener {
                    if (!succeeded) {
                        onLockResolved.run()
                        fragment.finishFragment()
                    }
                }
            }
        }
    }
}
