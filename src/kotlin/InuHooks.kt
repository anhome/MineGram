package desu.mintgram

import android.content.Context
import android.content.Intent
import android.os.Build
import desu.mintgram.helpers.CrashReporter
import desu.mintgram.helpers.LoginHelper
import desu.mintgram.helpers.OnboardingHelper
import desu.mintgram.helpers.ProxyVpnHelper
import desu.mintgram.helpers.ShortcutHelper
import desu.mintgram.helpers.UrlCleanerHelper
import desu.mintgram.helpers.cloud.CloudSettingsHelper
import desu.mintgram.helpers.channels.AutoReactionHelper
import desu.mintgram.helpers.channels.DeadChannelHelper
import desu.mintgram.helpers.font.FontHelper
import desu.mintgram.helpers.maps.MapsHelper
import desu.mintgram.helpers.security.PasscodeHelper
import desu.mintgram.helpers.theme.MonetHelper
import desu.mintgram.helpers.update.ApkInstaller
import desu.mintgram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.GestureDetector2
import org.telegram.ui.Components.GestureDetectorFixDoubleTap
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController


object InuHooks {
    @JvmStatic
    fun init(context: Context) {
        CrashReporter.install()
        InuConfig.load(context)
        desu.mintgram.helpers.FlipDeviceHelper.applyProfileForCurrentDevice()
        FontHelper.init(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            FontHelper.installGlobal()
        }
        syncDoubleTapDelay()
        syncAnimationSpeed()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.registerOverlayChangeReceiver(context)
        }
        UpdateHelper.clearPendingIfInstalled()
        ApkInstaller.dismissInstalledNotification()
        CloudSettingsHelper.attachAutoSyncListener()
        ProxyVpnHelper.init(context)
        Utilities.globalQueue.postRunnable { UrlCleanerHelper.preload() }
    }

    @JvmStatic
    fun onMessagesControllerCreated(messagesController: MessagesController, account: Int) {
        MapsHelper.syncMapProvider(messagesController)
        DeadChannelHelper.onControllerCreated(messagesController, account)
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(account).addObserver(
                NotificationCenter.NotificationCenterDelegate { id, acc, args ->
                    if (id != NotificationCenter.didReceiveNewMessages) return@NotificationCenterDelegate
                    @Suppress("UNCHECKED_CAST")
                    val messages = args[1] as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
                    for (msg in messages) onNewMessage(msg, acc)
                },
                NotificationCenter.didReceiveNewMessages,
            )
        }
    }

    fun onNewMessage(message: MessageObject, account: Int) {
        if (message.messageOwner != null) UpdateHelper.onNewMessage(message.messageOwner)
        desu.mintgram.helpers.dialogs.FolderHelper.onNewMessage(message, account)
        if (!DeadChannelHelper.isWatched(account, message.dialogId)) {
            AutoReactionHelper.onNewMessage(message, account)
        }
    }

    @JvmStatic
    fun syncAnimationSpeed() {
        try {
            Class.forName("android.animation.ValueAnimator")
                .getMethod("setDurationScale", Float::class.javaPrimitiveType)
                .invoke(null, 1f / InuConfig.ANIMATION_SPEED.value)
        } catch (_: Throwable) {
        }
        AnimatedFloat.inu_multiplier = InuConfig.ANIMATION_SPEED.value
    }

    @JvmStatic
    fun onUpdate(update: TLObject?, account: Int) {
        LoginHelper.onUpdate(update, account)
    }

    @JvmStatic
    fun handleIntent(activity: LaunchActivity, intent: Intent?): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
            || SearchRegistry.tryHandleDeepLink(activity, intent)
            || tryHandleFunDeepLink(activity, intent)
            || tryHandlePluginFile(activity, intent)
            || tryHandleIconPackFile(activity, intent)
            || ShortcutHelper.handleAction(activity, intent)
    }

    // Tapping a .plugin file (Files app, "Open with", etc. - see the VIEW intent-filter with
    // pathPattern=".*\.plugin" in AndroidManifest.xml) routes here. content:// URIs don't reliably
    // expose the file name in their path, so re-check the real display name before committing -
    // the manifest pathPattern match alone isn't a hard guarantee for every source app.
    private fun tryHandlePluginFile(activity: LaunchActivity, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        // content:// path segments are often opaque provider IDs (e.g. ".../file/7907"), not the
        // real file name - only trust the path segment for file:// URIs, where it genuinely is one.
        val name = if (uri.scheme == "content") {
            queryDisplayName(activity, uri) ?: uri.lastPathSegment?.substringAfterLast('/')
        } else {
            uri.lastPathSegment?.substringAfterLast('/') ?: queryDisplayName(activity, uri)
        }
        if (name == null || !name.endsWith(".plugin", ignoreCase = true)) return false
        val fragment = activity.actionBarLayout?.lastFragment ?: return false
        val installHook = PluginsBridge.installPluginFromUri ?: return false
        installHook(fragment, uri)
        return true
    }

    // Same one-tap flow as tryHandlePluginFile, for the .icon/.icons VIEW intent-filter in
    // AndroidManifest.xml. Icon packs live entirely in the library module (no Chaquopy dependency
    // like plugins), so this calls straight into helpers/icons — no PluginsBridge-style indirection
    // needed to cross a module boundary.
    private fun tryHandleIconPackFile(activity: LaunchActivity, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val name = if (uri.scheme == "content") {
            queryDisplayName(activity, uri) ?: uri.lastPathSegment?.substringAfterLast('/')
        } else {
            uri.lastPathSegment?.substringAfterLast('/') ?: queryDisplayName(activity, uri)
        }
        if (name == null || !(name.endsWith(".icon", ignoreCase = true) || name.endsWith(".icons", ignoreCase = true))) return false
        val fragment = activity.actionBarLayout?.lastFragment ?: return false
        desu.mintgram.helpers.icons.IconPackImportHelper.handle(fragment, uri)
        return true
    }

    private fun queryDisplayName(activity: LaunchActivity, uri: android.net.Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryHandleFunDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        val host = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')
        val (icon, text) = when (host) {
            "nya" -> R.raw.msg_emoji_cat to "meow~"
            "woof" -> R.raw.msg_emoji_activities to "woof :3"
            else -> return false
        }
        val fragment = activity.actionBarLayout?.lastFragment ?: return false
        BulletinFactory.of(fragment).createSimpleBulletin(icon, text).show()
        return true
    }

    @JvmStatic
    fun onAuthSuccess(account: Int) {
        PasscodeHelper.removeForAccount(account)
    }

    @JvmStatic
    fun onResume(launchActivity: LaunchActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.refreshMonetThemeIfChanged()
        }
        CrashReporter.maybeShowReportSheet(launchActivity)
        OnboardingHelper.maybeShow(launchActivity)
        ProxyVpnHelper.reconcile()
    }

    @JvmStatic
    fun syncDoubleTapDelay() {
        val delay = InuConfig.DOUBLE_TAP_DELAY.value
        GestureDetectorFixDoubleTap.GestureDetectorCompatImplBase.DOUBLE_TAP_TIMEOUT = delay
        GestureDetector2.DOUBLE_TAP_TIMEOUT = delay
    }

    @JvmStatic
    fun getCurrentAppIconLicense(): CharSequence {
        val current = LauncherIconController.LauncherIcon.entries
            .firstOrNull { LauncherIconController.isEnabled(it) }
        val resId = when (current) {
            LauncherIconController.LauncherIcon.DEFAULT -> R.string.InuAppIconLicenseMintgram
            else -> R.string.InuAppIconLicenseTelegram
        }
        return AndroidUtilities.replaceTags(getString(resId))
    }
}
