package desu.mintgram.helpers.security

import android.content.Context
import android.text.InputType
import androidx.core.content.edit
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

// Gates access to Mintgram's own fork settings behind a code word or biometric, user's choice -
// so someone who picks up an unlocked phone can't flip fork settings (paranoia mode, passcode,
// plugins, etc.) without it. Mirrors ArchiveLockHelper's shape; own prefs file for the same reason
// (the code hash must never land in InuConfig's cloud sync / export-to-json).
object SettingsLockHelper {
    enum class Method { CODE, BIOMETRIC }

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("mintgram_settings_lock", Context.MODE_PRIVATE)
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

    // Called right before pushing any fork settings page (stock Settings rows, search results,
    // tg://settings/inu/<slug> deep links - see call sites). Unlike ArchiveLockHelper.checkAccess
    // (which challenges after a fragment already exists, since Archive shares DialogsActivity's
    // own instance), this challenges BEFORE anything is pushed: onUnlocked runs the actual
    // presentFragment call, so locked-out settings content is never even constructed.
    @JvmStatic
    fun guardOpen(fragment: BaseFragment, onUnlocked: Runnable) {
        if (!isEnabled()) {
            onUnlocked.run()
            return
        }
        when (getMethod()) {
            Method.BIOMETRIC -> {
                val context = fragment.parentActivity
                if (context == null || !BiometricHelper.isSupported()) {
                    onUnlocked.run() // fail open - no way to challenge
                    return
                }
                BiometricHelper.gate(context, true, onSuccess = { onUnlocked.run() })
            }

            Method.CODE -> {
                if (!hasCode()) {
                    onUnlocked.run() // fail open - nothing configured to challenge with
                    return
                }
                showInputDialog(
                    fragment,
                    title = LocaleController.getString(R.string.InuSettingsLockPrompt),
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    onSubmit = { code ->
                        if (code.isNotEmpty() && verifyCode(code)) {
                            onUnlocked.run()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }
}
