package desu.mintgram.ui.drawer

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import desu.mintgram.InuConfig
import desu.mintgram.helpers.dialogs.DrawerMenuHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.DrawerLayoutContainer
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity

/**
 * Top-level drawer view: composes [DrawerHeaderView] -> [DrawerAccountPickerView] ->
 * [DrawerMenuView] inside a sliding panel, and owns open/close animation, edge-swipe touch
 * handling, scrim/immersive-push rendering and predictive-back. Replaces the old
 * `DrawerSwipeController` + `DrawerLayoutAdapter` + `SideMenultItemAnimator` combo — a
 * self-contained `FrameLayout` in the exteraGram `DrawerContainer` style, reimplemented against
 * Mintgram's own drawer cells.
 *
 * Unlike the old drawer, this view is a completely normal child of [host] (no off-canvas
 * layout/measure special-casing needed on the stock side) — it draws its own scrim/edge-shadow
 * internally and simply sits on top in z-order since it's added after the main content view.
 */
class DrawerContainer(private val host: DrawerLayoutContainer, private val nav: INavigationLayout) : FrameLayout(host.context) {

    private val drawerPanel: FrameLayout
    val headerView: DrawerHeaderView
    val accountPickerView: DrawerAccountPickerView
    val menuView: DrawerMenuView

    private var drawerPosition = 0f
    private var drawerOpened = false
    private var allowOpenDrawer = false
    private var maybeStartTracking = false
    private var startedTracking = false
    private var startedTrackingX = 0
    private var startedTrackingY = 0
    private var startedTrackingPointerId = 0
    private var velocityTracker: VelocityTracker? = null
    private var beginTrackingSent = false
    private var springAnimation: SpringAnimation? = null
    private var scrimOpacity = 0f
    private val scrimPaint = Paint()
    private var shadowLeft: Drawable? = null
    // Typed Any, not OnBackAnimationCallback: that class doesn't exist below API 33, and a
    // typed field would risk class-verification issues on older devices even though it's
    // only ever assigned/read from SDK-guarded code (same defensive pattern LaunchActivity
    // uses for its own onBackAnimationCallback field).
    private var backCallback: Any? = null

    val isDrawerOpened: Boolean get() = drawerOpened

    init {
        visibility = INVISIBLE
        setWillNotDraw(false)

        val width = minOf(
            AndroidUtilities.dp(320f),
            minOf(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) - AndroidUtilities.dp(56f)
        )

        drawerPanel = FrameLayout(context)
        drawerPanel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        // Raw FrameLayout.LayoutParams, not LayoutHelper.createFrame: `width` is already
        // pixels (via AndroidUtilities.dp above) — createFrame's int overloads re-apply
        // dp() to their args (see LayoutHelper.getSize), which would double-convert it.
        val panelLp = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.MATCH_PARENT)
        panelLp.gravity = Gravity.LEFT
        addView(drawerPanel, panelLp)
        drawerPanel.translationX = -width.toFloat()

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        drawerPanel.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        headerView = DrawerHeaderView(context, host)
        column.addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        accountPickerView = DrawerAccountPickerView(context)
        accountPickerView.nav = nav
        accountPickerView.onAccountSwitch = { closeDrawer(false) }
        column.addView(accountPickerView)

        menuView = DrawerMenuView(context)
        menuView.nav = nav
        menuView.drawerLayoutContainer = host
        column.addView(menuView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        headerView.onArrowClick = {
            val expand = !accountPickerView.isExpanded()
            headerView.setAccountsShown(expand, true)
            accountPickerView.setExpanded(expand, true)
        }
        headerView.onAvatarClick = {
            DrawerMenuHelper.openOwnProfile(nav)
            closeDrawer(false)
        }

        applyPanelCornerRadius()

        try {
            shadowLeft = resources.getDrawable(R.drawable.header_shadow)
        } catch (_: Exception) {
        }
    }

    fun setAllowOpenDrawer(value: Boolean, animated: Boolean) {
        allowOpenDrawer = value
        if (!allowOpenDrawer && drawerPosition != 0f) {
            if (!animated) {
                setDrawerPosition(0f)
                onDrawerAnimationEnd(false)
            } else closeDrawer(true)
        }
    }

    /** Full rebuild of header/account-picker/menu contents — call on open, account switch, or config change. */
    fun refreshContents() {
        val account = UserConfig.selectedAccount
        val user = MessagesController.getInstance(account).getUser(UserConfig.getInstance(account).getClientUserId())
        headerView.setUser(user, accountPickerView.isExpanded())
        accountPickerView.refresh()
        menuView.rebuild(account)
        applyPanelCornerRadius()
    }

    fun refreshTheme() {
        drawerPanel.setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground))
        invalidate()
    }

    private fun applyPanelCornerRadius() {
        val radiusDp = InuConfig.DRAWER_PANEL_CORNER_RADIUS.value
        if (radiusDp <= 0f) {
            drawerPanel.clipToOutline = false
            drawerPanel.elevation = 0f
            return
        }
        drawerPanel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, AndroidUtilities.dp(radiusDp).toFloat())
            }
        }
        drawerPanel.clipToOutline = true
        drawerPanel.invalidateOutline()
        // The old flat `shadowLeft` strip is drawn as a straight rectangle — it doesn't
        // follow rounded corners and leaves a visible seam there. Once the panel has an
        // outline, real elevation casts a shadow that already follows it, so use that
        // instead (onDraw skips the manual strip whenever rounding is active).
        drawerPanel.elevation = AndroidUtilities.dp(8f).toFloat()
    }

    /** Attaches a bottom-gravity view (the update-checker UI) inside the sliding panel. */
    fun attachBottomView(view: View) {
        drawerPanel.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun applyBottomPadding(padding: Int) {
        menuView.setPadding(menuView.paddingLeft, menuView.paddingTop, menuView.paddingRight, padding)
    }

    @Keep
    fun setDrawerPosition(value: Float) {
        val panelWidth = drawerPanel.width
        if (panelWidth <= 0) return
        drawerPosition = value.coerceIn(0f, panelWidth.toFloat())
        drawerPanel.translationX = drawerPosition - panelWidth
        val immersive = InuConfig.DRAWER_IMMERSIVE_ANIMATION.value
        val contentView = nav as? View
        contentView?.translationX = if (immersive) drawerPosition else 0f
        visibility = if (drawerPosition > 0) VISIBLE else INVISIBLE
        scrimOpacity = drawerPosition / panelWidth
        invalidate()
    }

    fun openDrawer(fast: Boolean) {
        if (!allowOpenDrawer) return
        animateTo(drawerPanel.width.toFloat(), fast, opening = true)
    }

    fun closeDrawer(fast: Boolean) {
        animateTo(0f, fast, opening = false)
    }

    private fun animateTo(target: Float, fast: Boolean, opening: Boolean) {
        springAnimation?.cancel()
        val panelWidth = drawerPanel.width.toFloat()
        if (panelWidth <= 0) return
        val startVelocity = if (fast) (if (opening) 6000f else -6000f) else 0f
        val anim = SpringAnimation(this, DRAWER_POSITION, target)
        anim.setSpring(
            SpringForce(target)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
        )
        anim.setStartVelocity(startVelocity)
        anim.addEndListener { _, canceled, _, _ -> if (!canceled) onDrawerAnimationEnd(opening) }
        springAnimation = anim
        anim.start()
    }

    private fun onDrawerAnimationEnd(opened: Boolean) {
        startedTracking = false
        springAnimation = null
        drawerOpened = opened
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        syncStatusBar(opened)
        if (opened) {
            refreshContents()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerPredictiveBack()
        } else {
            unregisterPredictiveBack()
        }
    }

    private fun syncStatusBar(opened: Boolean) {
        val activity = host.context as? LaunchActivity ?: return
        if (opened) {
            val bgKey = if (Theme.hasThemeKey(Theme.key_chats_menuTopBackground) &&
                Theme.getColor(Theme.key_chats_menuTopBackground) != 0
            ) Theme.key_chats_menuTopBackground else Theme.key_chats_menuTopBackgroundCats
            AndroidUtilities.setLightStatusBar(
                activity.window,
                ColorUtils.calculateLuminance(Theme.getColor(bgKey)) > 0.7,
            )
        } else {
            activity.checkSystemBarColors(false, true, false)
        }
    }

    private fun canTrackGesture(): Boolean {
        if (drawerOpened || drawerPosition > 0) return true
        if (nav.fragmentStack.size != 1) return false
        val top = nav.lastFragment
        if (top is DialogsActivity) {
            if (top.searchIsShowed) return false
            if (top.rightSlidingDialogContainer?.hasFragment() == true) return false
        }
        return true
    }

    private fun tabsOwnHorizontalSwipe(): Boolean {
        val top = nav.lastFragment as? DialogsActivity ?: return false
        val tabs = top.filterTabsView ?: return false
        return tabs.visibility == View.VISIBLE && !tabs.isFirstTabSelected
    }

    /** Called directly by [DrawerLayoutContainer.onTouchEvent] — not a normal Android touch-dispatch override. */
    fun handleTouchEvent(ev: MotionEvent?): Boolean {
        val panelWidth = drawerPanel.width
        if (panelWidth <= 0 || nav.checkTransitionAnimation()) {
            if (startedTracking || maybeStartTracking) {
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
            return false
        }
        if (ev != null && ev.action == MotionEvent.ACTION_DOWN && !startedTracking && maybeStartTracking) {
            maybeStartTracking = false
            velocityTracker?.recycle()
            velocityTracker = null
        }
        if (drawerOpened && ev != null && ev.x > drawerPosition && !startedTracking) {
            if (ev.action == MotionEvent.ACTION_UP) closeDrawer(false)
            return true
        }
        if (allowOpenDrawer && canTrackGesture()) {
            if (ev != null && (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE)
                && !startedTracking && !maybeStartTracking
            ) {
                startedTrackingX = ev.x.toInt()
                startedTrackingY = ev.y.toInt()
                startedTrackingPointerId = ev.getPointerId(0)
                maybeStartTracking = true
                springAnimation?.cancel()
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                else velocityTracker!!.clear()
                velocityTracker!!.addMovement(ev)
            } else if (ev != null && ev.action == MotionEvent.ACTION_MOVE
                && ev.getPointerId(0) == startedTrackingPointerId
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker!!.addMovement(ev)
                val dx = ev.x - startedTrackingX
                val dy = Math.abs(ev.y - startedTrackingY)
                val inEdgeZone = startedTrackingX <= AndroidUtilities.dp(EDGE_SAFE_ZONE_DP.toFloat())
                val openAngleOk = inEdgeZone || dx / 3f > dy
                val openSwipe = dx > 0 && openAngleOk && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.2f, true)
                    && (!tabsOwnHorizontalSwipe() || inEdgeZone)
                val closeSwipe = drawerOpened && dx < 0 && Math.abs(dx) >= dy && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.4f, true)
                if (maybeStartTracking && !startedTracking && (openSwipe || closeSwipe)) {
                    maybeStartTracking = false
                    startedTracking = true
                    beginTrackingSent = false
                    setDrawerPosition(drawerPosition + dx)
                    startedTrackingX = ev.x.toInt()
                    host.requestDisallowInterceptTouchEvent(true)
                } else if (startedTracking) {
                    if (!beginTrackingSent) beginTrackingSent = true
                    setDrawerPosition(drawerPosition + dx)
                    startedTrackingX = ev.x.toInt()
                }
            } else if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                && (ev.action == MotionEvent.ACTION_CANCEL
                    || ev.action == MotionEvent.ACTION_UP
                    || ev.action == MotionEvent.ACTION_POINTER_UP)
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                if (ev != null) velocityTracker!!.addMovement(ev)
                velocityTracker!!.computeCurrentVelocity(1000)
                if (startedTracking || (drawerPosition != 0f && drawerPosition != panelWidth.toFloat())) {
                    val velX = velocityTracker!!.xVelocity
                    val velY = velocityTracker!!.yVelocity
                    val back = drawerPosition < panelWidth / 2f
                        && (velX < 3500 || Math.abs(velX) < Math.abs(velY))
                        || velX < 0 && Math.abs(velX) >= 3500
                    if (!back) openDrawer(!drawerOpened && Math.abs(velX) >= 3500)
                    else closeDrawer(drawerOpened && Math.abs(velX) >= 3500)
                }
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        } else {
            if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                && (ev.action == MotionEvent.ACTION_CANCEL
                    || ev.action == MotionEvent.ACTION_UP
                    || ev.action == MotionEvent.ACTION_POINTER_UP)
            ) {
                startedTracking = false
                maybeStartTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return startedTracking
    }

    /** A descendant claimed the gesture via requestDisallowInterceptTouchEvent — see old DrawerSwipeController for why this matters. */
    fun handleParentDisallowIntercept() {
        if (startedTracking) return
        handleTouchEvent(null)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerPredictiveBack() {
        if (!InuConfig.DRAWER_PREDICTIVE_BACK.value || backCallback != null) return
        val activity = context as? Activity ?: return
        val callback = object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) {}
            override fun onBackProgressed(backEvent: BackEvent) {
                val panelWidth = drawerPanel.width.toFloat()
                setDrawerPosition(panelWidth * (1f - backEvent.progress))
            }

            override fun onBackInvoked() {
                closeDrawer(false)
            }

            override fun onBackCancelled() {
                openDrawer(false)
            }
        }
        backCallback = callback
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
    }

    private fun unregisterPredictiveBack() {
        val callback = backCallback ?: return
        backCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterPredictiveBackApi33(callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterPredictiveBackApi33(callback: Any) {
        val activity = context as? Activity ?: return
        activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback as OnBackAnimationCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelWidth = drawerPanel.width
        if (panelWidth <= 0) return
        val immersive = InuConfig.DRAWER_IMMERSIVE_ANIMATION.value
        if (!immersive && !InuConfig.DRAWER_HIDE_SCRIM.value && scrimOpacity > 0f) {
            scrimPaint.color = (0x99 * scrimOpacity).toInt() shl 24
            canvas.drawRect(drawerPosition, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        }
        // The flat strip is a straight rectangle — it doesn't follow rounded corners and
        // leaves a visible seam there. When rounding is active, drawerPanel's own elevation
        // (see applyPanelCornerRadius) casts a real shadow that already follows the outline.
        val shadow = shadowLeft
        if (shadow != null && drawerPosition > 0 && InuConfig.DRAWER_PANEL_CORNER_RADIUS.value <= 0f) {
            val alpha = (drawerPosition / AndroidUtilities.dp(20f)).coerceIn(0f, 1f)
            if (alpha != 0f) {
                shadow.setBounds(drawerPosition.toInt(), 0, drawerPosition.toInt() + shadow.intrinsicWidth, height)
                shadow.alpha = (0xff * alpha).toInt()
                shadow.draw(canvas)
            }
        }
    }

    companion object {
        private const val EDGE_SAFE_ZONE_DP = 25

        @JvmField
        val DRAWER_POSITION: FloatPropertyCompat<DrawerContainer> =
            object : FloatPropertyCompat<DrawerContainer>("drawerPosition") {
                override fun getValue(obj: DrawerContainer): Float = obj.drawerPosition
                override fun setValue(obj: DrawerContainer, value: Float) {
                    obj.setDrawerPosition(value)
                }
            }
    }
}
