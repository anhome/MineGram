package desu.mintgram.helpers.pillstack

import desu.mintgram.InuConfig
import org.telegram.messenger.NotificationCenter

/**
 * Ordered list of active pill ids (shown in [desu.mintgram.ui.pillstack.PillStackView], swipeable
 * top-to-bottom in that order) plus per-pill settings. Pills not in [activePills] are simply not
 * shown — there's no separate "hidden" bookkeeping list like exteraGram's, one list is enough.
 *
 * Called from both fork Kotlin (pill classes, settings page) and the stock `FragmentSearchField.java`
 * patch — every member Java needs to see is `@JvmStatic`.
 */
object PillStackConfig {
    private val pendingUpdates = HashSet<Int>()

    @JvmStatic
    var activePills: MutableList<Int>
        get() = parsePillsList(InuConfig.PILL_STACK_ACTIVE_PILLS.value)
        set(value) {
            InuConfig.PILL_STACK_ACTIVE_PILLS.value = value.joinToString(",")
        }

    @JvmStatic
    var infiniteScrolling: Boolean
        get() = InuConfig.PILL_STACK_INFINITE_SCROLLING.value
        set(value) {
            InuConfig.PILL_STACK_INFINITE_SCROLLING.value = value
        }

    @JvmStatic
    var lastActivePillId: Int
        get() = InuConfig.PILL_STACK_LAST_ACTIVE_ID.value
        set(value) {
            InuConfig.PILL_STACK_LAST_ACTIVE_ID.value = value
        }

    @JvmStatic
    var btcTargetCurrency: String
        get() = InuConfig.PILL_STACK_BTC_TARGET_CURRENCY.value
        set(value) {
            InuConfig.PILL_STACK_BTC_TARGET_CURRENCY.value = value
        }

    @JvmStatic
    var usdTargetCurrency: String
        get() = InuConfig.PILL_STACK_USD_TARGET_CURRENCY.value
        set(value) {
            InuConfig.PILL_STACK_USD_TARGET_CURRENCY.value = value
        }

    @JvmStatic
    var gramTargetCurrency: String
        get() = InuConfig.PILL_STACK_GRAM_TARGET_CURRENCY.value
        set(value) {
            InuConfig.PILL_STACK_GRAM_TARGET_CURRENCY.value = value
        }

    @JvmStatic
    fun isActive(pillId: Int): Boolean = pillId in activePills

    @JvmStatic
    fun setActive(pillId: Int, active: Boolean) {
        val current = activePills
        if (active) {
            if (pillId !in current) current.add(pillId)
        } else {
            current.remove(pillId)
        }
        activePills = current
        notifyLayoutChanged()
    }

    private fun parsePillsList(data: String): MutableList<Int> {
        if (data.isEmpty()) return mutableListOf()
        return data.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableList()
    }

    @JvmStatic
    fun notifyLayoutChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged)
    }

    /** Marks [pillIds] (or all registered pills, if none given) for a forced refresh next bind. */
    @JvmStatic
    fun notifySettingsChanged(vararg pillIds: Int) {
        if (pillIds.isEmpty()) {
            PillRegistry.pills.forEach { pendingUpdates.add(it.id) }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackSettingsChanged)
            return
        }
        pillIds.forEach { pendingUpdates.add(it) }
        NotificationCenter.getGlobalInstance().postNotificationName(
            NotificationCenter.pillStackSettingsChanged,
            *pillIds.toTypedArray(),
        )
    }

    @JvmStatic
    fun checkAndClearPendingUpdate(pillId: Int): Boolean = pendingUpdates.remove(pillId)

    @JvmStatic
    fun shouldUpdatePill(args: Array<out Any?>?, vararg pillIds: Int): Boolean {
        if (args.isNullOrEmpty() || pillIds.isEmpty()) return true
        return args.any { it is Int && it in pillIds }
    }
}
