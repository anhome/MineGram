package desu.mintgram.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import desu.mintgram.InuConfig
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.UrlCleanerHelper
import desu.mintgram.helpers.icons.IconPackStorage
import desu.mintgram.helpers.security.BiometricHelper
import desu.mintgram.helpers.security.ParanoiaHelper
import desu.mintgram.helpers.security.PasscodeHelper
import desu.mintgram.helpers.update.UpdateHelper
import desu.mintgram.ui.settings.ai.AiAssistantSettingsActivity
import desu.mintgram.ui.settings.feed.FeedChannelsActivity
import desu.mintgram.ui.settings.icons.IconPacksActivity
import desu.mintgram.ui.settings.pillstack.PillStackSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LiteMode
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SvgHelper
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Cells.TextDetailSettingsCell
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScrollSlidingTextTabStrip
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Labeled "Mint gram" in the root settings list (see `R.string.InuPrivacySecurity`) — the hub for
 * every setting Mintgram itself added, as opposed to `InuSettingsActivity` ("Inugram"), which is
 * the original base-fork settings. Categorized as real tabs per the user's request, not just
 * header groups in one long list.
 */
class PrivacySecurityActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPrivacySecurity)

    private var sourceRow: TextDetailSettingsCell? = null
    private var avatarCornersSlider: SliderCell? = null
    private var selectedCategory = Category.LOCKS

    private enum class Category(val titleRes: Int) {
        LOCKS(R.string.InuTabLocks),
        PRIVACY(R.string.InuTabPrivacy),
        MESSAGE_HISTORY(R.string.InuTabMessageHistory),
        APPEARANCE(R.string.InuTabAppearance),
        FEATURES(R.string.InuTabFeatures),
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun createView(context: Context): View {
        val view = super.createView(context)
        val strip = ScrollSlidingTextTabStrip(context)
        strip.setUseSameWidth(false)
        Category.entries.forEach { strip.addTextTab(it.ordinal, LocaleController.getString(it.titleRes)) }
        strip.visibility = View.VISIBLE
        strip.finishAddingTabs()
        strip.setDelegate(object : ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate {
            override fun onPageSelected(page: Int, forward: Boolean) {
                val category = Category.entries.getOrNull(page) ?: return
                if (category == selectedCategory) return
                selectedCategory = category
                listView.adapter.update(true)
            }

            override fun onPageScrolled(progress: Float) {}
        })
        attachSwipeBetweenTabs(context, strip)
        actionBar.setExtraHeight(AndroidUtilities.dp(44f))
        actionBar.addView(strip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT or Gravity.BOTTOM))
        listView.setPadding(listView.paddingLeft, listView.paddingTop + AndroidUtilities.dp(44f), listView.paddingRight, listView.paddingBottom)
        return view
    }

    /** Lets a clearly-horizontal drag anywhere on the list switch tabs, like a ViewPager would —
     * `ScrollSlidingTextTabStrip` itself has no built-in swipe support, it only drives/is driven
     * by an external pager. Uses `OnItemTouchListener` (not a plain touch listener) so it doesn't
     * fight the list's own vertical-scroll gesture recognition. */
    private fun attachSwipeBetweenTabs(context: Context, strip: ScrollSlidingTextTabStrip) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var tracking = false
        var handling = false

        listView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        tracking = true
                        handling = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (tracking && !handling) {
                            val dx = e.x - startX
                            val dy = e.y - startY
                            if (Math.abs(dx) > touchSlop * 3 && Math.abs(dx) > Math.abs(dy) * 2f) {
                                handling = true
                                return true
                            }
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        handling = false
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (!handling) return
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val dx = e.x - startX
                    val entries = Category.entries
                    val nextIndex = selectedCategory.ordinal + (if (dx < 0) 1 else -1)
                    entries.getOrNull(nextIndex)?.let { next ->
                        selectedCategory = next
                        strip.selectTabWithId(next.ordinal, 1f)
                        listView.adapter.update(true)
                    }
                }
                handling = false
                tracking = false
            }

            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        when (selectedCategory) {
            Category.LOCKS -> fillLocks(items)
            Category.PRIVACY -> fillPrivacy(items)
            Category.MESSAGE_HISTORY -> fillMessageHistory(items)
            Category.APPEARANCE -> fillAppearance(items)
            Category.FEATURES -> fillFeatures(items)
        }
    }

    private fun fillLocks(items: ArrayList<UItem>) {
        if (!PasscodeHelper.isSettingsHidden()) {
            items.add(
                UItem.asButton(
                    BUTTON_PASSCODE,
                    R.drawable.msg_permissions,
                    LocaleController.getString(R.string.InuPerAccountPasscode)
                )
            )
        }
        if (!ParanoiaHelper.isParanoia()) {
            items.add(
                UItem.asButton(
                    BUTTON_PARANOIA,
                    R.drawable.menu_hide_gift,
                    LocaleController.getString(R.string.InuParanoiaMode)
                )
            )
        }
        items.add(
            UItem.asButton(
                BUTTON_ARCHIVE_LOCK,
                R.drawable.msg_secret,
                LocaleController.getString(R.string.InuArchiveLock)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHAT_LOCK,
                R.drawable.msg_secret,
                LocaleController.getString(R.string.InuChatLock)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHAT_BACKUP,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuChatBackup)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_SETTINGS_LOCK,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuSettingsLock)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MEDIA_SEND_LOCK,
                R.drawable.msg_secret,
                LocaleController.getString(R.string.InuMediaSendLock)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CAMERA_LOCK,
                R.drawable.msg_secret,
                LocaleController.getString(R.string.InuCameraLock)
            )
        )
        if (BiometricHelper.isSupported()) {
            items.add(UItem.asShadow(null))
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuBiometricConfirm)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_DELETE_CHAT,
                    R.string.InuBiometricConfirmDeleteChat,
                    R.string.InuBiometricConfirmDeleteChatInfo,
                    { InuConfig.BIOMETRIC_CONFIRM_DELETE_CHAT.value }
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_LOGOUT,
                    R.string.InuBiometricConfirmLogout,
                    R.string.InuBiometricConfirmLogoutInfo,
                    { InuConfig.BIOMETRIC_CONFIRM_LOGOUT.value }
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL,
                    R.string.InuBiometricAllowDeviceCredential,
                    R.string.InuBiometricAllowDeviceCredentialInfo,
                    { InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.value }
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    private fun fillPrivacy(items: ArrayList<UItem>) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyTitle)))
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_MY_PHONE_NUMBER,
                LocaleController.getString(R.string.InuHideMyPhoneNumber)
            ).setChecked(InuConfig.HIDE_MY_PHONE_NUMBER.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_DRAFT_UPLOAD,
                R.string.InuDisableDraftUpload,
                R.string.InuDisableDraftUploadInfo,
                { InuConfig.DISABLE_DRAFT_UPLOAD.value }
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AUTO_READ_ARCHIVED_CHATS,
                R.string.InuAutoReadArchivedChats,
                R.string.InuAutoReadArchivedChatsInfo,
                { InuConfig.AUTO_READ_ARCHIVED_CHATS.value }
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuGhostMode)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_GHOST_HIDE_READ_STATUS,
                R.string.InuGhostHideReadStatus,
                R.string.InuGhostHideReadStatusInfo,
                { InuConfig.GHOST_HIDE_READ_STATUS.value }
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_GHOST_HIDE_ONLINE_STATUS,
                R.string.InuGhostHideOnlineStatus,
                R.string.InuGhostHideOnlineStatusInfo,
                { InuConfig.GHOST_HIDE_ONLINE_STATUS.value }
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_GHOST_HIDE_TYPING_STATUS,
                R.string.InuGhostHideTypingStatus,
                R.string.InuGhostHideTypingStatusInfo,
                { InuConfig.GHOST_HIDE_TYPING_STATUS.value }
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuStripTrackingParams)))
        items.add(
            UItem.asCheck(
                TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN,
                LocaleController.getString(R.string.InuStripTrackingParamsOnOpen)
            ).setChecked(InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE,
                LocaleController.getString(R.string.InuStripTrackingParamsOnPaste)
            ).setChecked(InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value)
        )
        items.add(UItem.asCustom(BUTTON_STRIP_TRACKING_PARAMS_SOURCE, getOrCreateSourceRow()))
        items.add(UItem.asShadow(null))
    }

    private fun fillMessageHistory(items: ArrayList<UItem>) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDeletedMessagesTitle)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_DELETED_MESSAGES,
                R.string.InuSaveDeletedMessages,
                R.string.InuSaveDeletedMessagesInfo,
                { InuConfig.SAVE_DELETED_MESSAGES.value }
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_EDITED_MESSAGES,
                R.string.InuSaveEditedMessages,
                R.string.InuSaveEditedMessagesInfo,
                { InuConfig.SAVE_EDITED_MESSAGES.value }
            )
        )
        items.add(UItem.asShadow(null))
    }

    private fun fillAppearance(items: ArrayList<UItem>) {
        items.add(
            UItem.asButton(
                BUTTON_ICON_REPLACEMENT,
                LocaleController.getString(R.string.InuIconReplacement),
                iconReplacementLabel(),
            )
        )
        items.add(UItem.asShadow(null))

        if (avatarCornersSlider == null) avatarCornersSlider = SliderCell(
            this.context, min = 0f, max = 28f,
            defaultValue = InuConfig.AVATAR_CORNERS.default,
            initialValue = InuConfig.AVATAR_CORNERS.value,
            step = 1f,
            title = LocaleController.getString(R.string.InuAvatarShape),
            format = {
                when {
                    it >= 28f -> LocaleController.getString(R.string.InuAvatarShapeCircle)
                    it <= 0f -> LocaleController.getString(R.string.InuAvatarShapeSquare)
                    else -> "${(100 - (it / 28f * 100).toInt())}%"
                }
            },
            onChanged = { InuConfig.AVATAR_CORNERS.value = it },
        )
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAvatarShape)))
        items.add(UItem.asCustom(avatarCornersSlider))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AVATAR_SINGLE_CORNER_RADIUS,
                R.string.InuAvatarSingleCornerRadius,
                R.string.InuAvatarSingleCornerRadiusInfo,
                { InuConfig.AVATAR_SINGLE_CORNER_RADIUS.value },
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAvatarShapeInfo)))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SNOW_EFFECT,
                R.string.InuSnowEffect,
                R.string.InuSnowEffectInfo,
                { InuConfig.SNOW_EFFECT.value },
            )
        )
        items.add(UItem.asShadow(null))
    }

    private fun fillFeatures(items: ArrayList<UItem>) {
        items.add(
            UItem.asButton(
                BUTTON_AI_ASSISTANT,
                R.drawable.magic_stick_solar,
                LocaleController.getString(R.string.InuAiAssistant)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_FEED,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.InuFeed)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PILL_STACK,
                R.drawable.weather_cloudy,
                LocaleController.getString(R.string.InuPillStack)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DRAWER,
                R.drawable.msg_list_solar,
                LocaleController.getString(R.string.InuNavigationDrawer)
            )
        )
        items.add(UItem.asShadow(null))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SUPER_OPTIMIZATION_MODE,
                R.string.InuSuperOptimizationMode,
                R.string.InuSuperOptimizationModeInfo,
                { InuConfig.SUPER_OPTIMIZATION_MODE.value }
            )
        )
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asButton(
                BUTTON_UPDATES,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuUpdatesRow)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PASSCODE -> presentFragment(PasscodeSettingsActivity())
            BUTTON_PARANOIA -> presentFragment(ParanoiaActivity())
            BUTTON_ARCHIVE_LOCK -> presentFragment(ArchiveLockSettingsActivity())
            BUTTON_CHAT_LOCK -> presentFragment(ChatLockSettingsActivity())
            BUTTON_CHAT_BACKUP -> presentFragment(ChatBackupSettingsActivity())
            BUTTON_SETTINGS_LOCK -> presentFragment(SettingsLockSettingsActivity())
            BUTTON_MEDIA_SEND_LOCK -> presentFragment(MediaSendLockSettingsActivity())
            BUTTON_CAMERA_LOCK -> presentFragment(CameraLockSettingsActivity())

            BUTTON_ICON_REPLACEMENT -> presentFragment(IconPacksActivity())
            BUTTON_AI_ASSISTANT -> presentFragment(AiAssistantSettingsActivity())
            BUTTON_FEED -> presentFragment(FeedChannelsActivity())
            BUTTON_PILL_STACK -> presentFragment(PillStackSettingsActivity())
            BUTTON_DRAWER -> presentFragment(DrawerSettingsActivity())
            BUTTON_UPDATES -> UpdateHelper.runManualCheck(this)

            TOGGLE_SUPER_OPTIMIZATION_MODE -> {
                val new = InuConfig.SUPER_OPTIMIZATION_MODE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                // Same refresh set LiteMode itself runs on a preset change (LiteMode.onFlagsUpdate) —
                // needed here too since this toggle overrides LiteMode.getValue() from outside,
                // bypassing that method's own change-tracking.
                SvgHelper.SvgDrawable.updateLiteValues()
                Theme.reloadWallpaper(true)
                AnimatedEmojiDrawable.updateAll()
            }

            TOGGLE_HIDE_MY_PHONE_NUMBER -> {
                val new = InuConfig.HIDE_MY_PHONE_NUMBER.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE -> {
                val new = InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_DRAFT_UPLOAD -> {
                val new = InuConfig.DISABLE_DRAFT_UPLOAD.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_AUTO_READ_ARCHIVED_CHATS -> {
                val new = InuConfig.AUTO_READ_ARCHIVED_CHATS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_GHOST_HIDE_READ_STATUS -> {
                val new = InuConfig.GHOST_HIDE_READ_STATUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_GHOST_HIDE_ONLINE_STATUS -> {
                val new = InuConfig.GHOST_HIDE_ONLINE_STATUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_GHOST_HIDE_TYPING_STATUS -> {
                val new = InuConfig.GHOST_HIDE_TYPING_STATUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SAVE_DELETED_MESSAGES -> {
                val new = InuConfig.SAVE_DELETED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SAVE_EDITED_MESSAGES -> {
                val new = InuConfig.SAVE_EDITED_MESSAGES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_DELETE_CHAT -> {
                val new = InuConfig.BIOMETRIC_CONFIRM_DELETE_CHAT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_LOGOUT -> {
                val new = InuConfig.BIOMETRIC_CONFIRM_LOGOUT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL -> {
                val new = InuConfig.BIOMETRIC_ALLOW_DEVICE_CREDENTIAL.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_AVATAR_SINGLE_CORNER_RADIUS -> {
                val new = InuConfig.AVATAR_SINGLE_CORNER_RADIUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SNOW_EFFECT -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.SNOW_EFFECT.toggle()
        }
    }

    private fun getOrCreateSourceRow(): TextDetailSettingsCell {
        sourceRow?.let { return it }
        val cell = TextDetailSettingsCell(context!!)
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        ))
        cell.setOnClickListener { showStripTrackingParamsOptions(it) }
        cell.setOnLongClickListener { showStripTrackingParamsOptions(it); true }
        sourceRow = cell
        refreshSourceRow()
        return cell
    }

    private fun refreshSourceRow() {
        val cell = sourceRow ?: return
        val title = LocaleController.getString(R.string.InuStripTrackingParamsSourceRow)
        val subtitle = LocaleController.formatString(
            R.string.InuStripTrackingParamsSource, UrlCleanerHelper.lastUpdated ?: "?",
        )
        cell.setTextAndValue(title, subtitle, false)
    }

    private fun showStripTrackingParamsOptions(anchor: View) {
        // scrim on the asCustom FrameLayout parent so ItemOptions picks up the section clip bg
        val scrim = (anchor.parent as? View) ?: anchor
        val opts = ItemOptions.makeOptions(this, scrim)
            .add(R.drawable.msg_download, LocaleController.getString(R.string.InuStripTrackingParamsUpdate)) {
                fetchLatestStripTrackingParams()
            }
        if (UrlCleanerHelper.isUsingOverride) {
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuStripTrackingParamsRevert)) {
                UrlCleanerHelper.resetToBundled()
                Utilities.globalQueue.postRunnable {
                    UrlCleanerHelper.preload()
                    AndroidUtilities.runOnUIThread { refreshSourceRow() }
                }
            }
        }
        opts.show()
    }

    private fun fetchLatestStripTrackingParams() {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { UrlCleanerHelper.fetchLatest() }
            UrlCleanerHelper.preload()
            AndroidUtilities.runOnUIThread {
                refreshSourceRow()
                val bulletin = BulletinFactory.of(this)
                result.fold(
                    onSuccess = { updated ->
                        val msg = if (updated) R.string.InuStripTrackingParamsUpdated
                        else R.string.InuStripTrackingParamsAlreadyLatest
                        bulletin.createSimpleBulletin(R.raw.contact_check, LocaleController.getString(msg)).show()
                    },
                    onFailure = { err ->
                        bulletin.createSimpleBulletin(
                            R.raw.error,
                            LocaleController.getString(R.string.InuStripTrackingParamsUpdateFailed),
                            err.message ?: "",
                        ).show()
                    },
                )
            }
        }
    }

    private fun iconReplacementLabel(): String = when (InuConfig.ICON_REPLACEMENT.value) {
        InuConfig.IconReplacementItem.SOLAR -> LocaleController.getString(R.string.InuIconReplacementSolar)
        InuConfig.IconReplacementItem.CUSTOM ->
            IconPackStorage.getPack(InuConfig.ACTIVE_CUSTOM_ICON_PACK_ID.value)?.name
                ?: LocaleController.getString(R.string.InuIconReplacementOff)
        else -> LocaleController.getString(R.string.InuIconReplacementOff)
    }

    companion object {
        private val BUTTON_PASSCODE = InuUtils.generateId()
        private val BUTTON_PARANOIA = InuUtils.generateId()
        private val BUTTON_ARCHIVE_LOCK = InuUtils.generateId()
        private val BUTTON_CHAT_LOCK = InuUtils.generateId()
        private val BUTTON_CHAT_BACKUP = InuUtils.generateId()
        private val BUTTON_SETTINGS_LOCK = InuUtils.generateId()
        private val BUTTON_MEDIA_SEND_LOCK = InuUtils.generateId()
        private val BUTTON_CAMERA_LOCK = InuUtils.generateId()
        private val TOGGLE_HIDE_MY_PHONE_NUMBER = InuUtils.generateId()
        private val TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN = InuUtils.generateId()
        private val TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE = InuUtils.generateId()
        private val BUTTON_STRIP_TRACKING_PARAMS_SOURCE = InuUtils.generateId()
        private val TOGGLE_DISABLE_DRAFT_UPLOAD = InuUtils.generateId()
        private val TOGGLE_AUTO_READ_ARCHIVED_CHATS = InuUtils.generateId()
        private val TOGGLE_GHOST_HIDE_READ_STATUS = InuUtils.generateId()
        private val TOGGLE_GHOST_HIDE_ONLINE_STATUS = InuUtils.generateId()
        private val TOGGLE_GHOST_HIDE_TYPING_STATUS = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SAVE_EDITED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_DELETE_CHAT = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_LOGOUT = InuUtils.generateId()
        private val TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val TOGGLE_AVATAR_SINGLE_CORNER_RADIUS = InuUtils.generateId()
        private val TOGGLE_SNOW_EFFECT = InuUtils.generateId()
        private val BUTTON_AI_ASSISTANT = InuUtils.generateId()
        private val BUTTON_FEED = InuUtils.generateId()
        private val BUTTON_PILL_STACK = InuUtils.generateId()
        private val BUTTON_DRAWER = InuUtils.generateId()
        private val TOGGLE_SUPER_OPTIMIZATION_MODE = InuUtils.generateId()
        private val BUTTON_UPDATES = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "privacy-security",
            titleRes = R.string.InuPrivacySecurity,
            iconRes = R.drawable.msg_permissions,
            factory = ::PrivacySecurityActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-my-phone-number", R.string.InuHideMyPhoneNumber, TOGGLE_HIDE_MY_PHONE_NUMBER),
                SearchRegistry.Entry("strip-tracking-params", R.string.InuStripTrackingParamsOnOpen, TOGGLE_STRIP_TRACKING_PARAMS_ON_OPEN),
                SearchRegistry.Entry("strip-tracking-params-paste", R.string.InuStripTrackingParamsOnPaste, TOGGLE_STRIP_TRACKING_PARAMS_ON_PASTE),
                SearchRegistry.Entry("disable-draft-upload", R.string.InuDisableDraftUpload, TOGGLE_DISABLE_DRAFT_UPLOAD),
                SearchRegistry.Entry("auto-read-archived-chats", R.string.InuAutoReadArchivedChats, TOGGLE_AUTO_READ_ARCHIVED_CHATS),
                SearchRegistry.Entry("ghost-hide-read-status", R.string.InuGhostHideReadStatus, TOGGLE_GHOST_HIDE_READ_STATUS),
                SearchRegistry.Entry("ghost-hide-online-status", R.string.InuGhostHideOnlineStatus, TOGGLE_GHOST_HIDE_ONLINE_STATUS),
                SearchRegistry.Entry("ghost-hide-typing-status", R.string.InuGhostHideTypingStatus, TOGGLE_GHOST_HIDE_TYPING_STATUS),
                SearchRegistry.Entry("save-deleted-messages", R.string.InuSaveDeletedMessages, TOGGLE_SAVE_DELETED_MESSAGES),
                SearchRegistry.Entry("save-edited-messages", R.string.InuSaveEditedMessages, TOGGLE_SAVE_EDITED_MESSAGES),
                SearchRegistry.Entry("biometric-confirm-delete-chat", R.string.InuBiometricConfirmDeleteChat, TOGGLE_BIOMETRIC_DELETE_CHAT),
                SearchRegistry.Entry("biometric-confirm-logout", R.string.InuBiometricConfirmLogout, TOGGLE_BIOMETRIC_LOGOUT),
                SearchRegistry.Entry("biometric-allow-device-credential", R.string.InuBiometricAllowDeviceCredential, TOGGLE_BIOMETRIC_DEVICE_CREDENTIAL),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("avatar-shape", R.string.InuAvatarShape, TOGGLE_AVATAR_SINGLE_CORNER_RADIUS),
                SearchRegistry.Entry("snow-effect", R.string.InuSnowEffect, TOGGLE_SNOW_EFFECT),
                SearchRegistry.Entry("ai-assistant", R.string.InuAiAssistant, BUTTON_AI_ASSISTANT),
                SearchRegistry.Entry("feed", R.string.InuFeed, BUTTON_FEED),
                SearchRegistry.Entry("pill-stack", R.string.InuPillStack, BUTTON_PILL_STACK),
                SearchRegistry.Entry("navigation-drawer-entry", R.string.InuNavigationDrawer, BUTTON_DRAWER),
                SearchRegistry.Entry("super-optimization-mode", R.string.InuSuperOptimizationMode, TOGGLE_SUPER_OPTIMIZATION_MODE),
                SearchRegistry.Entry("updates", R.string.InuUpdatesRow, BUTTON_UPDATES),
            ),
        )
    }
}
