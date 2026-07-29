package desu.mintgram.helpers.security

import android.content.Context
import android.text.InputType
import android.util.Log
import androidx.core.content.edit
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

private const val TAG = "MintgramArchiveLock"

// Gates the Archive folder behind a code word or biometric, user's choice. State lives in its
// own prefs file (like PasscodeHelper/ParanoiaHelper) so the code hash never lands in
// InuConfig's cloud sync / export-to-json.
object ArchiveLockHelper {
    enum class Method { CODE, BIOMETRIC }

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("mintgram_archive_lock", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("enabled", enabled) }
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

    // Called from DialogsActivity.onFragmentCreate right after folderId is read; folderId == 1
    // is the Archive. Blocks nothing by itself - on failure/cancel it just pops the fragment
    // that already (harmlessly) exists, same as a normal back navigation.
    //
    // onLockShown/onLockResolved bracket the actual challenge (biometric prompt or code dialog)
    // so the caller can blur the archive content for that window - a shoulder-surfer shouldn't
    // be able to read chat names/previews while the prompt is up. Only fired around a real
    // challenge: the "fail open" early-returns below (lock enabled but nothing configured to
    // challenge with) never call onLockShown, so onLockResolved never needs to run for them.
    @JvmStatic
    fun checkAccess(fragment: BaseFragment, onLockShown: Runnable, onLockResolved: Runnable) {
        Log.d(TAG, "checkAccess: enabled=${isEnabled()} method=${getMethod()} hasCode=${hasCode()} biometricSupported=${BiometricHelper.isSupported()}")
        if (!isEnabled()) return
        when (getMethod()) {
            Method.BIOMETRIC -> {
                val context = fragment.context ?: return
                if (!BiometricHelper.isSupported()) return // no way to challenge - fail open rather than lock the user out
                Log.d(TAG, "checkAccess: running onLockShown (biometric)")
                onLockShown.run()
                BiometricHelper.gate(
                    context, true,
                    onSuccess = { Log.d(TAG, "checkAccess: biometric success, running onLockResolved"); onLockResolved.run() },
                    onFailure = { Log.d(TAG, "checkAccess: biometric failure, running onLockResolved"); onLockResolved.run(); fragment.finishFragment() },
                )
            }

            Method.CODE -> {
                if (!hasCode()) return
                Log.d(TAG, "checkAccess: running onLockShown (code)")
                onLockShown.run()
                var succeeded = false
                val dialog = showInputDialog(
                    fragment,
                    title = LocaleController.getString(R.string.InuArchiveLockPrompt),
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
