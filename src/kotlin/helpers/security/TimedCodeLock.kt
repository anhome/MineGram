package desu.mintgram.helpers.security

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import androidx.core.content.edit
import desu.mintgram.ui.showInputDialog
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.ui.LaunchActivity

// Shared "code word or biometric" gate with a configurable how-often-to-ask frequency, used by
// MediaSendLockHelper and CameraLockHelper. Own prefs file per instance (like ArchiveLockHelper/
// SettingsLockHelper/ChatLockHelper) so the code hash never lands in InuConfig's cloud sync /
// export-to-json. Unlike those three (which just gate opening a single screen once), this also
// tracks a timed "session" so a chatty action like sending photos doesn't have to reprompt on
// every single tap when the user picked the delayed-reask frequency.
open class TimedCodeLock(prefsName: String) {
    enum class Method { CODE, BIOMETRIC }
    enum class Frequency { EVERY_TIME, TIMEOUT }

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("enabled", enabled) }
    }

    fun getMethod(): Method =
        if (prefs.getString("method", "code") == "biometric") Method.BIOMETRIC else Method.CODE

    fun setMethod(method: Method) {
        prefs.edit { putString("method", if (method == Method.BIOMETRIC) "biometric" else "code") }
    }

    fun hasCode(): Boolean = prefs.contains("codeHash") && prefs.contains("codeSalt")
    fun setCode(code: String) {
        SecretHash.store(prefs, "codeHash", "codeSalt", code)
    }

    private fun verifyCode(code: String): Boolean = SecretHash.verify(prefs, "codeHash", "codeSalt", code)

    fun getFrequency(): Frequency =
        if (prefs.getString("frequency", "every") == "timeout") Frequency.TIMEOUT else Frequency.EVERY_TIME

    fun setFrequency(frequency: Frequency) {
        prefs.edit { putString("frequency", if (frequency == Frequency.TIMEOUT) "timeout" else "every") }
    }

    fun getTimeoutMinutes(): Int = prefs.getInt("timeoutMinutes", 5)
    fun setTimeoutMinutes(minutes: Int) {
        prefs.edit { putInt("timeoutMinutes", minutes) }
    }

    @Volatile
    private var sessionUnlockedUntil = 0L

    // One-shot: set right before re-running the gated action after a successful challenge, so
    // that immediate re-entry (which re-runs the same needsChallenge() check) doesn't prompt
    // again for what's actually still the same already-approved action.
    @Volatile
    private var bypassNextCheck = false

    fun needsChallenge(): Boolean {
        if (!isEnabled()) return false
        if (bypassNextCheck) {
            bypassNextCheck = false
            return false
        }
        if (getFrequency() == Frequency.TIMEOUT && SystemClock.elapsedRealtime() < sessionUnlockedUntil) return false
        return true
    }

    private fun markUnlocked() {
        bypassNextCheck = true
        if (getFrequency() == Frequency.TIMEOUT) {
            sessionUnlockedUntil = SystemClock.elapsedRealtime() + getTimeoutMinutes() * 60_000L
        }
    }

    /**
     * Runs [onUnlocked] immediately if nothing needs challenging right now, otherwise prompts
     * (code dialog or biometric) against the current foreground fragment and only runs it on
     * success. Fails open (runs [onUnlocked] without prompting) when there's nothing configured
     * to challenge with, or no fragment to anchor a prompt to - same philosophy as
     * ArchiveLockHelper/SettingsLockHelper: never lock the user out of the app itself.
     */
    fun challenge(promptTitleRes: Int, onUnlocked: Runnable) {
        if (!needsChallenge()) {
            onUnlocked.run()
            return
        }
        val fragment = LaunchActivity.getLastFragment()
        val activity = fragment?.parentActivity
        if (fragment == null || activity == null) {
            onUnlocked.run()
            return
        }
        when (getMethod()) {
            Method.BIOMETRIC -> {
                if (!BiometricHelper.isSupported()) {
                    onUnlocked.run()
                    return
                }
                BiometricHelper.gate(
                    activity, true,
                    onSuccess = { markUnlocked(); onUnlocked.run() },
                )
            }

            Method.CODE -> {
                if (!hasCode()) {
                    onUnlocked.run()
                    return
                }
                showInputDialog(
                    fragment,
                    title = LocaleController.getString(promptTitleRes),
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    onSubmit = { code ->
                        if (code.isNotEmpty() && verifyCode(code)) {
                            markUnlocked()
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
