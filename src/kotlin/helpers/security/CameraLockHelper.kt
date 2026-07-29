package desu.mintgram.helpers.security

import org.telegram.messenger.R

// Gates opening the in-app camera (photo/video, from the chat attachment sheet) behind a code
// word or biometric. Hooked at ChatAttachAlertPhotoLayout.openCameraByClick(), the single funnel
// all of that screen's "open the camera" triggers (button tap, permission-granted callback, etc.)
// already go through - see call sites of openCameraByClick/openCameraWithPermissionCheck.
object CameraLockHelper : TimedCodeLock("mintgram_camera_lock") {
    // Returns true if the camera open was intercepted (caller must return without opening the
    // camera); [retry] is re-invoked, and expected to pass this same gate, once unlocked.
    @JvmStatic
    fun gate(retry: Runnable): Boolean {
        if (!needsChallenge()) return false
        challenge(R.string.InuCameraLockPrompt, retry)
        return true
    }
}
