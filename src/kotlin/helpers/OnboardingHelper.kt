package desu.mintgram.helpers

import desu.mintgram.InuConfig
import desu.mintgram.ui.OnboardingBottomSheet
import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import java.util.concurrent.atomic.AtomicBoolean

object OnboardingHelper {

    private val sheetShown = AtomicBoolean(false)

    fun maybeShow(activity: LaunchActivity) {
        if (InuConfig.ONBOARDING_SHOWN.value) return
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated) return
        if (!sheetShown.compareAndSet(false, true)) return
        try {
            OnboardingBottomSheet(activity).show()
            InuConfig.ONBOARDING_SHOWN.value = true
        } catch (_: Throwable) {
            sheetShown.set(false)
        }
    }
}
