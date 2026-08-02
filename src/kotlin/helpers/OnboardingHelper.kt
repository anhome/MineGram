package desu.mintgram.helpers

import desu.mintgram.InuConfig
import desu.mintgram.ui.FlipOnboardingBottomSheet
import desu.mintgram.ui.OnboardingBottomSheet
import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import java.util.concurrent.atomic.AtomicBoolean

object OnboardingHelper {

    private val sheetShown = AtomicBoolean(false)

    fun maybeShow(activity: LaunchActivity) {
        val flipWelcome = InuConfig.FLIP_DEVICE_MODE.value && !InuConfig.FLIP_ONBOARDING_SHOWN.value
        if (!flipWelcome && !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated) return
        if (!flipWelcome && InuConfig.ONBOARDING_SHOWN.value) return
        if (!sheetShown.compareAndSet(false, true)) return
        try {
            if (flipWelcome) {
                FlipOnboardingBottomSheet(activity).show()
                InuConfig.FLIP_ONBOARDING_SHOWN.value = true
            } else {
                OnboardingBottomSheet(activity).show()
            }
            InuConfig.ONBOARDING_SHOWN.value = true
        } catch (_: Throwable) {
            sheetShown.set(false)
        }
    }
}
