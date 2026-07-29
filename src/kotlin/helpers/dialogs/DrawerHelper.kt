package desu.mintgram.helpers.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import desu.mintgram.InuConfig
import desu.mintgram.helpers.update.UpdateHelper
import desu.mintgram.ui.drawer.DrawerContainer
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.MenuDrawable
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.ContactsActivity
import org.telegram.ui.DialogsActivity
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.LaunchActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.SettingsActivity
import org.telegram.ui.UpdateLayoutWrapper
import org.telegram.tgnet.ConnectionsManager

@SuppressLint("StaticFieldLeak")
object DrawerHelper {

    private var container: DrawerContainer? = null
    private var themeObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var proxyObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var updateLayout: IUpdateLayout? = null
    private var updateObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var updateObserverAccount: Int = -1
    private var menuDrawableRef: MenuDrawable? = null

    @JvmStatic
    @JvmOverloads
    fun createMainFragment(args: Bundle? = null): BaseFragment {
        if (InuConfig.NAVIGATION_DRAWER.value) return DialogsActivity(args)
        val main = MainTabsActivity()
        if (args != null) main.prepareDialogsActivity(args)
        return main
    }

    /** Root fragment on startup: stock `addFragmentToStack` + navigation drawer wiring. */
    @JvmStatic
    fun setupMainFragment(activity: LaunchActivity, layout: INavigationLayout, dlc: DrawerLayoutContainer) {
        layout.addFragmentToStack(createMainFragment())
        if (InuConfig.NAVIGATION_DRAWER.value) setup(activity, dlc, layout)
    }

    /** Push the main fragment, forwarding a pending search query when tabs are present. */
    @JvmStatic
    fun addMainFragmentToStack(layout: INavigationLayout, searchQuery: String?) {
        val main = createMainFragment()
        val dialogs = if (main is MainTabsActivity) main.prepareDialogsActivity(null) else main as DialogsActivity
        if (searchQuery != null) dialogs.setInitialSearchString(searchQuery)
        layout.addFragmentToStack(main, INavigationLayout.FORCE_NOT_ATTACH_VIEW)
        ensureSetup(layout)
    }

    /**
     * Wire the side drawer onto the activity's container once (idempotent), or
     * refresh its contents if already wired. Needed for login/relogin flows that
     * present the main fragment outside [setupMainFragment] — without this the
     * post-login `DialogsActivity` has no drawer.
     */
    @JvmStatic
    fun ensureSetup(layout: INavigationLayout?) {
        if (!InuConfig.NAVIGATION_DRAWER.value || layout == null) return
        val dlc = layout.drawerLayoutContainer ?: return
        if (dlc.inu_drawer == null) {
            setup(dlc.context, dlc, layout)
        } else {
            container?.refreshContents()
            rebindPerAccountObservers()
            updateLayout?.updateAppUpdateViews(UserConfig.selectedAccount, false)
            applyUpdateBottomPadding()
            refreshMenuButton(false)
        }
    }

    @JvmStatic
    fun setup(
        context: Context,
        drawerLayoutContainer: DrawerLayoutContainer,
        actionBarLayout: INavigationLayout,
    ) {
        val c = DrawerContainer(drawerLayoutContainer, actionBarLayout)
        container = c
        drawerLayoutContainer.inu_drawer = c
        drawerLayoutContainer.addView(c, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        c.setAllowOpenDrawer(true, false)
        c.menuView.onProxySwitchToggled = ::applyProxyEnabled
        c.refreshContents()

        installThemeObserver()
        installProxyObserver()
        installUpdateLayout(context as? Activity, c)
    }

    private fun installUpdateLayout(activity: Activity?, drawerContainer: DrawerContainer) {
        if (activity == null) return
        // Stock UpdateLayoutWrapper: paints accent across the navbar inset, propagates
        // paddingBottom to the row so centered content stays in the visible 44dp.
        val wrapper = UpdateLayoutWrapper(activity)
        drawerContainer.attachBottomView(wrapper)
        // UpdateLayoutWrapper.setPadding propagates to children — but only children that exist
        // at call time. The row is added later by UpdateLayout.createUpdateUI, so always
        // re-propagate on every inset dispatch instead of guarding by current value.
        wrapper.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            v.requestLayout()
            insets
        }
        wrapper.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight)

        // Overwrites any prior UpdateLayout, releasing its Activity ref (Activity.recreate path).
        val ul = ApplicationLoader.applicationLoaderInstance
            ?.takeUpdateLayout(activity, wrapper) ?: return
        updateLayout = ul
        applyUpdateBottomPadding()
        ul.updateAppUpdateViews(UserConfig.selectedAccount, false)

        // Observer lambda closes only over singleton state — registered once per process.
        if (updateObserver == null) {
            val obs = NotificationCenter.NotificationCenterDelegate { id, _, args ->
                val current = updateLayout ?: return@NotificationCenterDelegate
                when (id) {
                    NotificationCenter.appUpdateAvailable -> {
                        val animated = args.getOrNull(0) as? Boolean ?: true
                        current.updateAppUpdateViews(UserConfig.selectedAccount, animated)
                        applyUpdateBottomPadding()
                        refreshMenuButton(animated)
                    }

                    NotificationCenter.appUpdateLoading -> {
                        current.updateFileProgress(null)
                        current.updateAppUpdateViews(UserConfig.selectedAccount, true)
                        refreshMenuButton(true)
                    }

                    NotificationCenter.fileLoadProgressChanged -> {
                        current.updateFileProgress(args)
                        refreshMenuButton(true)
                    }

                    NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed -> {
                        val name = args.getOrNull(0) as? String ?: return@NotificationCenterDelegate
                        val doc = SharedConfig.pendingAppUpdate?.document ?: return@NotificationCenterDelegate
                        if (name == FileLoader.getAttachFileName(doc)) {
                            current.updateAppUpdateViews(UserConfig.selectedAccount, true)
                            refreshMenuButton(true)
                        }
                    }
                }
            }
            updateObserver = obs
            val global = NotificationCenter.getGlobalInstance()
            global.addObserver(obs, NotificationCenter.appUpdateAvailable)
            global.addObserver(obs, NotificationCenter.appUpdateLoading)
        }
        rebindPerAccountObservers()
        refreshMenuButton(false)
    }

    private fun rebindPerAccountObservers() {
        val obs = updateObserver ?: return
        val newAccount = UserConfig.selectedAccount
        if (updateObserverAccount == newAccount) return
        if (updateObserverAccount != -1) {
            val prev = NotificationCenter.getInstance(updateObserverAccount)
            prev.removeObserver(obs, NotificationCenter.fileLoadProgressChanged)
            prev.removeObserver(obs, NotificationCenter.fileLoaded)
            prev.removeObserver(obs, NotificationCenter.fileLoadFailed)
        }
        updateObserverAccount = newAccount
        val acct = NotificationCenter.getInstance(newAccount)
        acct.addObserver(obs, NotificationCenter.fileLoadProgressChanged)
        acct.addObserver(obs, NotificationCenter.fileLoaded)
        acct.addObserver(obs, NotificationCenter.fileLoadFailed)
    }

    /**
     * Updates the menu drawable used as a back-button in the drawer-mode DialogsActivity to reflect
     * the current pending-update state: exclamation when available, circular progress while
     * downloading. Mirrors stock Telegram 11.4.2's `updateMenuButton`.
     */
    @JvmStatic
    fun refreshMenuButton(drawable: MenuDrawable?, animated: Boolean) {
        // The patch seeds with a non-null drawable on DialogsActivity creation; we cache the
        // reference so notification observers can update the icon even when DialogsActivity
        // isn't the top fragment (e.g. user is in AboutActivity when the check completes).
        if (drawable != null) menuDrawableRef = drawable
        val d = drawable ?: menuDrawableRef ?: return
        val type: Int
        val downloadProgress: Float
        if (SharedConfig.isAppUpdateAvailable()) {
            val doc = SharedConfig.pendingAppUpdate.document
            val fileName = FileLoader.getAttachFileName(doc)
            if (UpdateHelper.isPendingStart || FileLoader.getInstance(UserConfig.selectedAccount).isLoadingFile(fileName)) {
                type = MenuDrawable.TYPE_UDPATE_DOWNLOADING
                downloadProgress = ImageLoader.getInstance().getFileProgress(fileName) ?: 0f
            } else {
                type = MenuDrawable.TYPE_UDPATE_AVAILABLE
                downloadProgress = 0f
            }
        } else {
            type = MenuDrawable.TYPE_DEFAULT
            downloadProgress = 0f
        }
        d.setType(type, animated)
        d.setUpdateDownloadProgress(downloadProgress, animated)
    }

    private fun refreshMenuButton(animated: Boolean) {
        refreshMenuButton(null, animated)
    }

    private fun applyUpdateBottomPadding() {
        val extra = if (SharedConfig.isAppUpdateAvailable()) {
            dp(44f) + AndroidUtilities.navigationBarHeight
        } else 0
        container?.applyBottomPadding(extra)
    }

    private fun installThemeObserver() {
        if (themeObserver != null) return
        val obs = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.didSetNewTheme || id == NotificationCenter.reloadInterface) {
                refreshTheme()
            }
        }
        themeObserver = obs
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.didSetNewTheme)
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.reloadInterface)
    }

    private fun installProxyObserver() {
        if (proxyObserver != null) return
        val obs = NotificationCenter.NotificationCenterDelegate { id, _, _ ->
            if (id == NotificationCenter.proxySettingsChanged) {
                container?.menuView?.rebuild(UserConfig.selectedAccount)
            }
        }
        proxyObserver = obs
        NotificationCenter.getGlobalInstance().addObserver(obs, NotificationCenter.proxySettingsChanged)
    }

    private fun applyProxyEnabled(enabled: Boolean) {
        val proxy = if (enabled) SharedConfig.currentProxy else null
        MessagesController.getGlobalMainSettings().edit()
            .putBoolean("proxy_enabled", enabled && proxy != null)
            .apply()
        if (proxy != null) {
            ConnectionsManager.setProxySettings(
                true, proxy.address, proxy.port,
                proxy.username, proxy.password, proxy.secret
            )
        } else {
            ConnectionsManager.setProxySettings(false, "", 0, "", "", "")
        }
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.proxySettingsChanged)
    }

    private fun refreshTheme() {
        val c = container ?: return
        c.refreshTheme()
        c.refreshContents()
        // Static sunDrawable persists across theme changes; refreshContents rebinds the
        // header but never re-syncs the day/night frame.
        c.headerView.updateSunDrawable(Theme.isCurrentThemeDark())
    }

    @JvmStatic
    fun notifyDataChanged() {
        container?.refreshContents()
    }

    /** Old Layout back-button hook: toggles the side drawer. Returns false if unavailable. */
    @JvmStatic
    fun toggleDrawer(parentLayout: INavigationLayout?): Boolean {
        val c = parentLayout?.drawerLayoutContainer?.inu_drawer ?: return false
        if (c.isDrawerOpened) c.closeDrawer(false) else c.openDrawer(false)
        return true
    }

    @JvmStatic
    fun addDialogsActivityOptions(instance: DialogsActivity, io: ItemOptions) {
        val bottomTabsHidden = MainTabsHelper.isHidden

        if (bottomTabsHidden) {
            io.add(R.drawable.left_status_profile, getString(R.string.MyProfile)) {
                val args = Bundle()
                args.putLong("user_id", UserConfig.getInstance(instance.currentAccount).getClientUserId())
                args.putBoolean("my_profile", true)
                instance.presentFragment(ProfileActivity(args))
            }
        }

        if (bottomTabsHidden || MainTabsHelper.isContactsTabHidden) {
            io.add(R.drawable.msg_contacts, getString(R.string.Contacts)) {
                val args = Bundle()
                args.putBoolean("needPhonebook", true)
                instance.presentFragment(ContactsActivity(args))
            }
        }

        if (bottomTabsHidden) {
            io.add(R.drawable.msg_settings_old, getString(R.string.Settings)) {
                instance.presentFragment(SettingsActivity())
            }
        }
    }
}
