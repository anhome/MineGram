package desu.mintgram.ui.settings.pillstack

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.core.content.ContextCompat
import desu.mintgram.SearchRegistry
import desu.mintgram.helpers.InuUtils
import desu.mintgram.helpers.pillstack.PillRegistry
import desu.mintgram.helpers.pillstack.PillStackConfig
import desu.mintgram.helpers.pillstack.PillType
import desu.mintgram.ui.settings.SettingsPageActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.Weather

class PillStackSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPillStack)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPillStackInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPillStackPills)))
        val active = PillStackConfig.activePills
        for (pill in PillRegistry.pills) {
            items.add(
                UItem.asRippleCheck(ITEM_BASE + pill.id, pill.name)
                    .setChecked(pill.id in active)
            )
        }
        items.add(UItem.asShadow(null))

        if (PillType.WEATHER.id in active && !hasLocationPermission()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuWeatherPill)))
            items.add(UItem.asButton(BUTTON_GRANT_LOCATION, LocaleController.getString(R.string.InuWeatherLocationPermissionGrant)).red())
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuWeatherLocationPermissionInfo)))
        }

        items.add(
            UItem.asRippleCheck(TOGGLE_INFINITE_SCROLLING, LocaleController.getString(R.string.InuPillStackInfiniteScrolling))
                .setChecked(PillStackConfig.infiniteScrolling)
        )
        items.add(UItem.asShadow(null))
    }

    private fun hasLocationPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_GRANT_LOCATION -> {
                Weather.getUserLocation(true) {
                    listView.adapter.update(true)
                    PillStackConfig.notifySettingsChanged(PillType.WEATHER.id)
                }
            }

            TOGGLE_INFINITE_SCROLLING -> {
                val new = !PillStackConfig.infiniteScrolling
                PillStackConfig.infiniteScrolling = new
                (view as? TextCheckCell)?.isChecked = new
            }
            else -> {
                val pillId = item.id - ITEM_BASE
                if (PillRegistry.getPillInfo(pillId) != null) {
                    val new = !PillStackConfig.isActive(pillId)
                    PillStackConfig.setActive(pillId, new)
                    (view as? TextCheckCell)?.isChecked = new
                    if (pillId == PillType.WEATHER.id) listView.adapter.update(true)
                }
            }
        }
    }

    companion object {
        private const val ITEM_BASE = 23_000
        private val TOGGLE_INFINITE_SCROLLING = InuUtils.generateId()
        private val BUTTON_GRANT_LOCATION = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "pill-stack",
            titleRes = R.string.InuPillStack,
            iconRes = R.drawable.weather_cloudy,
            factory = ::PillStackSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("pill-stack-infinite-scrolling", R.string.InuPillStackInfiniteScrolling, TOGGLE_INFINITE_SCROLLING),
            ),
        )
    }
}
