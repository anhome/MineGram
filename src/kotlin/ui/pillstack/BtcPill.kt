package desu.mintgram.ui.pillstack

import android.content.Context
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

class BtcPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    RatePill(context, resourcesProvider, "BTC", 2, R.drawable.pillstack_btc, ColoredBackground(-1071598, -1608430)) {

    companion object {
        private val CACHE = RatePill.RateCache()
    }

    override fun cache(): RatePill.RateCache = CACHE
    override fun getPillId(): Int = PillType.BTC.id
    override fun getTargetSelection(): String = PillStackConfig.btcTargetCurrency
    override fun setTargetSelection(value: String) {
        PillStackConfig.btcTargetCurrency = value
    }
}
