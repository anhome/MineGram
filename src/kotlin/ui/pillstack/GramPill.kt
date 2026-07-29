package desu.mintgram.ui.pillstack

import android.content.Context
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillType
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

class GramPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    RatePill(context, resourcesProvider, "TON", 3, R.drawable.mini_gram_16, ColoredBackground()) {

    companion object {
        private val CACHE = RatePill.RateCache()
    }

    override fun cache(): RatePill.RateCache = CACHE
    override fun getPillId(): Int = PillType.GRAM.id
    override fun getTargetSelection(): String = PillStackConfig.gramTargetCurrency
    override fun setTargetSelection(value: String) {
        PillStackConfig.gramTargetCurrency = value
    }
}
