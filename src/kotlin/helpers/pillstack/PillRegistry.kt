package desu.mintgram.helpers.pillstack

import android.content.Context
import desu.mintgram.ui.pillstack.BasePill
import desu.mintgram.ui.pillstack.BatteryPill
import desu.mintgram.ui.pillstack.BtcPill
import desu.mintgram.ui.pillstack.CachePill
import desu.mintgram.ui.pillstack.GramPill
import desu.mintgram.ui.pillstack.ProxyPill
import desu.mintgram.ui.pillstack.UsdPill
import desu.mintgram.ui.pillstack.WeatherPill
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.IconBackgroundColors

data class PillInfo(
    val id: Int,
    val name: CharSequence,
    val iconRes: Int,
    val iconColorTop: Int,
    val iconColorBottom: Int,
    val creator: (Context, Theme.ResourcesProvider?) -> BasePill,
)

object PillRegistry {
    val pills: List<PillInfo> = listOf(
        PillInfo(
            PillType.WEATHER.id, LocaleController.getString(R.string.InuWeatherPill), R.drawable.weather_cloudy,
            IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
        ) { ctx, rp -> WeatherPill(ctx, rp) },
        PillInfo(
            PillType.GRAM.id, "GRAM", R.drawable.settings_gram_24,
            IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom,
        ) { ctx, rp -> GramPill(ctx, rp) },
        PillInfo(
            PillType.BTC.id, "BTC", R.drawable.pillstack_btc_settings,
            IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
        ) { ctx, rp -> BtcPill(ctx, rp) },
        PillInfo(
            PillType.USD.id, "USD", R.drawable.pillstack_usd_settings,
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
        ) { ctx, rp -> UsdPill(ctx, rp) },
        PillInfo(
            PillType.CACHE.id, LocaleController.getString(R.string.StorageUsage), R.drawable.msg_filled_storageusage,
            IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
        ) { ctx, rp -> CachePill(ctx, rp) },
        PillInfo(
            PillType.PROXY.id, LocaleController.getString(R.string.Proxy), R.drawable.drawer_proxy_on,
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
        ) { ctx, rp -> ProxyPill(ctx, rp) },
        PillInfo(
            PillType.BATTERY.id, LocaleController.getString(R.string.InuBatteryPill), R.drawable.msg2_battery_solar,
            IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
        ) { ctx, rp -> BatteryPill(ctx, rp) },
    )

    @JvmStatic
    fun getPillInfo(id: Int): PillInfo? = pills.find { it.id == id }
}
