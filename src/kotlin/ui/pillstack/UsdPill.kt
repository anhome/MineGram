package desu.mintgram.ui.pillstack

import android.content.Context
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillStackCurrencies
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

class UsdPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    RatePill(context, resourcesProvider, "USD", 2, R.drawable.pillstack_usd, ColoredBackground(-14840995, -15172775)) {

    companion object {
        private val CACHE = RatePill.RateCache()
    }

    override fun cache(): RatePill.RateCache = CACHE
    override fun getPillId(): Int = PillType.USD.id

    override fun getTargetSelection(): String {
        val current = PillStackConfig.usdTargetCurrency
        return if (current.equals("USD", ignoreCase = true)) "AUTO" else current
    }

    override fun setTargetSelection(value: String) {
        PillStackConfig.usdTargetCurrency = value
    }

    override fun getTargetCurrencies(): Array<String> = PillStackCurrencies.getTargetCurrencies("USD")
}
