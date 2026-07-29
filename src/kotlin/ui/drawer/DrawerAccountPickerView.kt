package desu.mintgram.ui.drawer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.mintgram.InuConfig
import desu.mintgram.helpers.dialogs.AccountOrderHelper
import desu.mintgram.helpers.security.PasscodeHelper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.INavigationLayout
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LoginActivity

/**
 * The drawer's collapsible account list — its own internal RecyclerView so drag-reordering
 * (ported from the old `DrawerHelper.attachAccountReorder`) stays isolated from the plain
 * [DrawerMenuView] below it. Expand/collapse is driven by [DrawerHeaderView]'s arrow.
 */
class DrawerAccountPickerView(context: Context) : FrameLayout(context) {

    private val listView: RecyclerListView
    private val adapter: Adapter

    var nav: INavigationLayout? = null
    var onAccountSwitch: (() -> Unit)? = null

    private var expanded = false
    private var expandAnimator: ValueAnimator? = null

    init {
        listView = RecyclerListView(context)
        listView.layoutManager = LinearLayoutManager(context)
        adapter = Adapter()
        listView.adapter = adapter
        listView.setVerticalScrollBarEnabled(false)
        listView.clipToPadding = false
        listView.isNestedScrollingEnabled = false
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        listView.setOnItemClickListener { view, _ ->
            when (view) {
                is DrawerUserCell -> {
                    LaunchActivity.instance?.switchToAccount(view.accountNumber, true)
                    onAccountSwitch?.invoke()
                }

                is DrawerAddCell -> {
                    val availableAccount = (UserConfig.MAX_ACCOUNT_COUNT - 1 downTo 0)
                        .firstOrNull { !UserConfig.getInstance(it).isClientActivated }
                    if (availableAccount != null) {
                        nav?.presentFragment(LoginActivity(availableAccount))
                        onAccountSwitch?.invoke()
                    }
                }
            }
        }

        attachAccountReorder()

        layoutParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0)
        visibility = GONE
    }

    fun refresh() {
        adapter.reload()
        adapter.notifyDataSetChanged()
    }

    fun isExpanded(): Boolean = expanded

    fun setExpanded(value: Boolean, animated: Boolean) {
        if (expanded == value) return
        expanded = value
        expandAnimator?.cancel()

        if (!animated) {
            val lp = layoutParams
            lp.height = if (expanded) LayoutHelper.WRAP_CONTENT else 0
            layoutParams = lp
            visibility = if (expanded) VISIBLE else GONE
            return
        }

        visibility = VISIBLE
        measure(
            MeasureSpec.makeMeasureSpec((parent as? View)?.width ?: 0, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = measuredHeight
        val from = if (expanded) 0 else targetHeight
        val to = if (expanded) targetHeight else 0
        expandAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            interpolator = CubicBezierInterpolator.EASE_OUT
            addUpdateListener {
                val lp = layoutParams
                lp.height = it.animatedValue as Int
                layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded) visibility = GONE
                }
            })
            start()
        }
    }

    private fun canAddAccount(): Boolean =
        UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT

    private fun attachAccountReorder() {
        val callback = object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = true

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val dirs = if (vh.itemView is DrawerUserCell) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
                return makeMovementFlags(dirs, 0)
            }

            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (target.itemView !is DrawerUserCell) return false
                return adapter.swapAccounts(source.adapterPosition, target.adapterPosition)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    listView.cancelClickRunnables(false)
                    vh?.itemView?.isPressed = true
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.isPressed = false
                AccountOrderHelper.setVisibleOrder(adapter.accountNumbers)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(listView)
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        val accountNumbers = ArrayList<Int>()

        init {
            reload()
        }

        fun reload() {
            accountNumbers.clear()
            for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(a).isClientActivated
                    && (a == UserConfig.selectedAccount || !PasscodeHelper.isAccountHidden(a))
                ) {
                    accountNumbers.add(a)
                }
            }
            AccountOrderHelper.sort(accountNumbers)
        }

        override fun getItemCount(): Int = accountNumbers.size + if (canAddAccount()) 1 else 0

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemViewType(position: Int): Int = if (position < accountNumbers.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = if (viewType == 0) DrawerUserCell(context) else DrawerAddCell(context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(InuConfig.DRAWER_ROW_HEIGHT.value))
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position < accountNumbers.size) {
                (holder.itemView as DrawerUserCell).setAccount(accountNumbers[position])
            }
        }

        fun swapAccounts(fromPosition: Int, toPosition: Int): Boolean {
            if (fromPosition < 0 || toPosition < 0 || fromPosition >= accountNumbers.size || toPosition >= accountNumbers.size) return false
            val tmp = accountNumbers[fromPosition]
            accountNumbers[fromPosition] = accountNumbers[toPosition]
            accountNumbers[toPosition] = tmp
            notifyItemMoved(fromPosition, toPosition)
            return true
        }
    }
}
