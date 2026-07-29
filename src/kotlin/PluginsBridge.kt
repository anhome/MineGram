package desu.mintgram

import android.net.Uri
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment

// The plugin engine (desu.mintgram.plugins.*) lives in TMessagesProj_App, not here - Chaquopy
// only supports application modules, so it can't be added to this library module. This interface
// lets the app module register itself as the hook implementation without inverting the
// app->library dependency direction. Null until the app module's ApplicationLoaderImpl registers
// it; stock call sites must treat null as "no plugins loaded" and behave exactly as stock.
object PluginsBridge {
    interface Hooks {
        // Return null to cancel/drop the update or send. Non-null results are only used for the
        // cancellation signal - stock call sites don't substitute a modified object back in, since
        // the surrounding methods capture the original parameter in lambdas throughout their body.
        fun executeUpdatesHook(type: String, account: Int, updates: TLRPC.Updates): TLRPC.Updates?
        fun executeSendMessageHook(account: Int, params: SendMessagesHelper.SendMessageParams): SendMessagesHelper.SendMessageParams?
    }

    @JvmField
    var instance: Hooks? = null

    // Lets InuSettingsActivity (this module) open the app module's PluginsActivity without a
    // direct reference to it. Registered once at startup; null only if the app module didn't wire
    // it up (shouldn't happen in practice, but the settings entry just no-ops if so).
    @JvmField
    var pluginsActivityFactory: (() -> BaseFragment)? = null

    // Lets InuHooks.handleIntent (this module) route a tapped .plugin file into the app module's
    // install-confirmation flow (PluginsController.showInstallDialog) without a direct reference.
    @JvmField
    var installPluginFromUri: ((BaseFragment, Uri) -> Unit)? = null
}
