package desu.mintgram.ui.settings

import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import desu.mintgram.InuConfig
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** The drawer's own settings screen: on/off toggle, tile reordering, and behavior toggles. */
class DrawerSettingsActivity : SettingsPageActivity() {

    private var avatarPreviewImage: BackupImageView? = null
    private var avatarPreviewLp: FrameLayout.LayoutParams? = null
    private var avatarSizeSlider: SliderCell? = null
    private var rowHeightSlider: SliderCell? = null
    private var cornerRadiusSlider: SliderCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuNavigationDrawer)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_NAVIGATION_DRAWER,
                R.string.InuNavigationDrawer,
                R.string.InuNavigationDrawerInfo,
                { InuConfig.NAVIGATION_DRAWER.value },
            )
        )
        items.add(UItem.asShadow(null))

        if (InuConfig.NAVIGATION_DRAWER.value) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuDrawerBehavior)))
            items.add(mkSubPageButton(BUTTON_MENU_ORDER, LocaleController.getString(R.string.InuDrawerMenuOrder)))
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_IMMERSIVE,
                    R.string.InuDrawerImmersive,
                    R.string.InuDrawerImmersiveInfo,
                    { InuConfig.DRAWER_IMMERSIVE_ANIMATION.value },
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_PREDICTIVE_BACK,
                        R.string.InuDrawerPredictiveBack,
                        R.string.InuDrawerPredictiveBackInfo,
                        { InuConfig.DRAWER_PREDICTIVE_BACK.value },
                    )
                )
            }
            if (!InuConfig.DRAWER_IMMERSIVE_ANIMATION.value) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_HIDE_SCRIM,
                        R.string.InuDrawerHideScrim,
                        R.string.InuDrawerHideScrimInfo,
                        { InuConfig.DRAWER_HIDE_SCRIM.value },
                    )
                )
            }
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_HIDE_THEME_BUTTON,
                    R.string.InuDrawerHideThemeButton,
                    0,
                    { InuConfig.DRAWER_HIDE_THEME_TOGGLE.value },
                )
            )
            items.add(UItem.asShadow(null))

            items.add(UItem.asHeader(LocaleController.getString(R.string.InuDrawerAppearance)))
            items.add(
                UItem.asButton(
                    BUTTON_NAME_OVERRIDE,
                    LocaleController.getString(R.string.InuDrawerNameOverride),
                    nameOverrideValueLabel(),
                )
            )
            items.add(UItem.asCustom(buildAvatarPreviewCell(context), 120))
            items.add(UItem.asCustom(buildAvatarSizeSlider(context)))
            items.add(UItem.asCustom(buildRowHeightSlider(context)))
            items.add(UItem.asCustom(buildCornerRadiusSlider(context)))
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuDrawerAppearanceInfo)))
        }
    }

    private fun nameOverrideSelectedIndex(): Int {
        val v = InuConfig.DRAWER_NAME_OVERRIDE.value
        return when {
            v.isEmpty() -> 0
            v == "MintGram" -> 1
            else -> 2
        }
    }

    private fun nameOverrideValueLabel(): String {
        val v = InuConfig.DRAWER_NAME_OVERRIDE.value
        return v.ifEmpty { LocaleController.getString(R.string.InuDrawerNameOverrideReal) }
    }

    private fun showNicknameDialog() {
        val ctx = context ?: return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(8f), AndroidUtilities.dp(24f), 0)
        }
        val current = InuConfig.DRAWER_NAME_OVERRIDE.value
        val input = EditText(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setText(if (current == "MintGram") "" else current)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))
        showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.InuDrawerNameOverrideCustom))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        InuConfig.DRAWER_NAME_OVERRIDE.value = name
                        listView.adapter.update(true)
                    }
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ -> listView.adapter.update(true) }
                .create()
        )
    }

    private fun buildAvatarPreviewCell(context: Context): View {
        val wrapper = FrameLayout(context)
        val account = UserConfig.selectedAccount
        val user = MessagesController.getInstance(account).getUser(UserConfig.getInstance(account).getClientUserId())
        val image = BackupImageView(context)
        val sizePx = AndroidUtilities.dp(InuConfig.DRAWER_AVATAR_SIZE.value)
        val lp = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
        image.imageReceiver.setRoundRadius(sizePx / 2)
        if (user != null) {
            val avatarDrawable = AvatarDrawable(user)
            image.setForUserOrChat(user, avatarDrawable)
        }
        wrapper.addView(image, lp)
        avatarPreviewImage = image
        avatarPreviewLp = lp
        return wrapper
    }

    private fun buildAvatarSizeSlider(context: Context): SliderCell {
        val slider = avatarSizeSlider ?: SliderCell(
            context, min = 40f, max = 96f,
            defaultValue = InuConfig.DRAWER_AVATAR_SIZE.default,
            initialValue = InuConfig.DRAWER_AVATAR_SIZE.value,
            step = 4f,
            title = LocaleController.getString(R.string.InuDrawerAvatarSize),
            format = { "${it.toInt()}dp" },
            onChanged = {
                InuConfig.DRAWER_AVATAR_SIZE.value = it
                val sizePx = AndroidUtilities.dp(it)
                avatarPreviewLp?.let { lp ->
                    lp.width = sizePx
                    lp.height = sizePx
                }
                avatarPreviewImage?.imageReceiver?.setRoundRadius(sizePx / 2)
                avatarPreviewImage?.requestLayout()
            },
        )
        avatarSizeSlider = slider
        return slider
    }

    private fun buildRowHeightSlider(context: Context): SliderCell {
        val slider = rowHeightSlider ?: SliderCell(
            context, min = 40f, max = 64f,
            defaultValue = InuConfig.DRAWER_ROW_HEIGHT.default,
            initialValue = InuConfig.DRAWER_ROW_HEIGHT.value,
            step = 2f,
            title = LocaleController.getString(R.string.InuDrawerRowHeight),
            format = { "${it.toInt()}dp" },
            onChanged = { InuConfig.DRAWER_ROW_HEIGHT.value = it },
        )
        rowHeightSlider = slider
        return slider
    }

    private fun buildCornerRadiusSlider(context: Context): SliderCell {
        val slider = cornerRadiusSlider ?: SliderCell(
            context, min = 0f, max = 32f,
            defaultValue = InuConfig.DRAWER_PANEL_CORNER_RADIUS.default,
            initialValue = InuConfig.DRAWER_PANEL_CORNER_RADIUS.value,
            step = 2f,
            title = LocaleController.getString(R.string.InuDrawerCornerRadius),
            format = { if (it <= 0f) LocaleController.getString(R.string.InuDrawerCornerRadiusOff) else "${it.toInt()}dp" },
            onChanged = { InuConfig.DRAWER_PANEL_CORNER_RADIUS.value = it },
        )
        cornerRadiusSlider = slider
        return slider
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_NAVIGATION_DRAWER -> {
                InuConfig.NAVIGATION_DRAWER.toggle()
                listView.adapter.update(true)
                showRestartBulletin()
            }

            BUTTON_MENU_ORDER -> presentFragment(DrawerMenuOrderActivity())

            BUTTON_NAME_OVERRIDE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuDrawerNameOverrideReal),
                    "MintGram",
                    LocaleController.getString(R.string.InuDrawerNameOverrideCustom),
                ),
                nameOverrideSelectedIndex(),
            ) { which ->
                when (which) {
                    0 -> {
                        InuConfig.DRAWER_NAME_OVERRIDE.value = ""
                        listView.adapter.update(true)
                    }

                    1 -> {
                        InuConfig.DRAWER_NAME_OVERRIDE.value = "MintGram"
                        listView.adapter.update(true)
                    }

                    2 -> showNicknameDialog()
                }
            }

            TOGGLE_IMMERSIVE -> {
                InuConfig.DRAWER_IMMERSIVE_ANIMATION.toggle()
                listView.adapter.update(true)
            }

            TOGGLE_HIDE_SCRIM -> {
                val new = InuConfig.DRAWER_HIDE_SCRIM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_PREDICTIVE_BACK -> {
                val new = InuConfig.DRAWER_PREDICTIVE_BACK.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_THEME_BUTTON -> {
                val new = InuConfig.DRAWER_HIDE_THEME_TOGGLE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
        }
    }

    companion object {
        private val TOGGLE_NAVIGATION_DRAWER = InuUtils.generateId()
        private val BUTTON_MENU_ORDER = InuUtils.generateId()
        private val TOGGLE_IMMERSIVE = InuUtils.generateId()
        private val TOGGLE_PREDICTIVE_BACK = InuUtils.generateId()
        private val TOGGLE_HIDE_SCRIM = InuUtils.generateId()
        private val TOGGLE_HIDE_THEME_BUTTON = InuUtils.generateId()
        private val BUTTON_NAME_OVERRIDE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "navigation-drawer",
            titleRes = R.string.InuNavigationDrawer,
            iconRes = R.drawable.msg_list_solar,
            factory = ::DrawerSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("navigation-drawer", R.string.InuNavigationDrawer, TOGGLE_NAVIGATION_DRAWER),
                SearchRegistry.Entry("drawer-menu-order", R.string.InuDrawerMenuOrder, BUTTON_MENU_ORDER),
                SearchRegistry.Entry("drawer-immersive-animation", R.string.InuDrawerImmersive, TOGGLE_IMMERSIVE),
                SearchRegistry.Entry("drawer-predictive-back", R.string.InuDrawerPredictiveBack, TOGGLE_PREDICTIVE_BACK),
                SearchRegistry.Entry("drawer-hide-scrim", R.string.InuDrawerHideScrim, TOGGLE_HIDE_SCRIM),
                SearchRegistry.Entry("drawer-hide-theme-button", R.string.InuDrawerHideThemeButton, TOGGLE_HIDE_THEME_BUTTON),
                SearchRegistry.Entry("drawer-name-override", R.string.InuDrawerNameOverride, BUTTON_NAME_OVERRIDE),
            ),
        )
    }
}
