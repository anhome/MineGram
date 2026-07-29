package desu.mintgram

import android.content.Intent
import desu.mintgram.helpers.security.ParanoiaHelper
import desu.mintgram.helpers.security.SettingsLockHelper
import desu.mintgram.ui.settings.AnnoyancesSettingsActivity
import desu.mintgram.ui.settings.AppearanceSettingsActivity
import desu.mintgram.ui.settings.BehaviorSettingsActivity
import desu.mintgram.ui.settings.ChatsSettingsActivity
import desu.mintgram.ui.settings.DialogsSettingsActivity
import desu.mintgram.ui.settings.DrawerSettingsActivity
import desu.mintgram.ui.settings.InuSettingsActivity
import desu.mintgram.ui.settings.MessagesSettingsActivity
import desu.mintgram.ui.settings.PrivacySecurityActivity
import desu.mintgram.ui.settings.SettingsPageActivity
import desu.mintgram.ui.settings.TranslatorSettingsActivity
import desu.mintgram.ui.settings.UserProfileSettingsActivity
import desu.mintgram.ui.settings.ai.AiAssistantSettingsActivity
import desu.mintgram.ui.settings.feed.FeedChannelsActivity
import desu.mintgram.ui.settings.fonts.FontStackActivity
import desu.mintgram.ui.settings.fonts.FontsSettingsActivity
import desu.mintgram.ui.settings.icons.IconPacksActivity
import desu.mintgram.ui.settings.pillstack.PillStackSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

object SearchRegistry {
    /**
     * @param itemId optional — runtime [org.telegram.ui.Components.UItem.id] to highlight on open.
     */
    data class Entry(val slug: String, val titleRes: Int, val itemId: Int = -1)

    data class Page(
        val slug: String,
        val titleRes: Int,
        val iconRes: Int,
        val factory: () -> SettingsPageActivity,
        val entries: List<Entry> = emptyList(),
    )

    private val pages: List<Page> by lazy {
        listOf(
            InuSettingsActivity.PAGE,
            AppearanceSettingsActivity.PAGE,
            FontsSettingsActivity.PAGE,
            FontStackActivity.PAGE,
            ChatsSettingsActivity.PAGE,
            MessagesSettingsActivity.PAGE,
            DialogsSettingsActivity.PAGE,
            UserProfileSettingsActivity.PAGE,
            AnnoyancesSettingsActivity.PAGE,
            BehaviorSettingsActivity.PAGE,
            TranslatorSettingsActivity.PAGE,
            PrivacySecurityActivity.PAGE,
            AiAssistantSettingsActivity.PAGE,
            FeedChannelsActivity.PAGE,
            PillStackSettingsActivity.PAGE,
            IconPacksActivity.PAGE,
            DrawerSettingsActivity.PAGE,
        )
    }

    private data class Target(val page: Page, val entry: Entry?)

    private val targetBySlug: Map<String, Target> by lazy {
        buildMap {
            for (page in pages) {
                fun add(slug: String, target: Target) {
                    require(put(slug, target) == null) { "SearchRegistry: duplicate slug '$slug'" }
                }
                add(page.slug, Target(page, null))
                for (entry in page.entries) add(entry.slug, Target(page, entry))
            }
        }
    }

    private val slugByItemId: Map<Int, String> by lazy {
        buildMap {
            for (page in pages) for (entry in page.entries) {
                if (entry.itemId != -1) putIfAbsent(entry.itemId, entry.slug)
            }
        }
    }

    fun deepLinkForItemId(itemId: Int): String? =
        slugByItemId[itemId]?.let { "tg://settings/inu/$it" }

    @JvmStatic
    fun extendSearchArray(
        stock: Array<ProfileActivity.SearchAdapter.SearchResult>,
        f: BaseFragment,
    ): Array<ProfileActivity.SearchAdapter.SearchResult> {
        if (ParanoiaHelper.shouldHideSettings()) return stock
        val extra = ArrayList<ProfileActivity.SearchAdapter.SearchResult>()
        for (page in pages) {
            val pageTitle = LocaleController.getString(page.titleRes)
            val parent = "${LocaleController.getString(R.string.InuSettings)} → $pageTitle"
            extra.add(
                ProfileActivity.SearchAdapter.SearchResult(
                    guidFor(page.slug),
                    pageTitle,
                    LocaleController.getString(R.string.InuSettings),
                    page.iconRes,
                ) { SettingsLockHelper.guardOpen(f) { f.presentFragment(page.factory()) } }.withLink("tg://settings/inu/${page.slug}")
            )
            for (entry in page.entries) {
                val title = LocaleController.getString(entry.titleRes)
                extra.add(
                    ProfileActivity.SearchAdapter.SearchResult(
                        guidFor(entry.slug),
                        title,
                        parent,
                        page.iconRes,
                    ) {
                        SettingsLockHelper.guardOpen(f) { f.presentFragment(page.factory().withHighlight(entry.itemId)) }
                    }.withLink("tg://settings/inu/${entry.slug}")
                )
            }
        }
        return stock + extra.toTypedArray()
    }

    @JvmStatic
    fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        if (ParanoiaHelper.shouldHideSettings()) return false
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        // accept both `tg://settings/inu/<slug>` (host=settings) and `tg:settings/inu/<slug>` (opaque)
        val segs = when (uri.host) {
            "settings" -> uri.pathSegments
            null -> uri.schemeSpecificPart?.removePrefix("//")
                ?.removePrefix("settings/")?.split('/')
                ?: return false

            else -> return false
        }
        if (segs.size < 2 || segs[0] != "inu") return false
        val target = targetBySlug[segs[1]] ?: return false
        val hostFragment = activity.actionBarLayout?.lastFragment ?: return false
        SettingsLockHelper.guardOpen(hostFragment) {
            val fragment = target.page.factory()
            target.entry?.let { fragment.withHighlight(it.itemId) }
            activity.actionBarLayout.presentFragment(fragment)
        }
        return true
    }

    // stable guid from slug, high bit set to avoid stock guid range (<1000).
    private fun guidFor(slug: String): Int = 0x10000000 or (slug.hashCode() and 0x00FFFFFF)
}
