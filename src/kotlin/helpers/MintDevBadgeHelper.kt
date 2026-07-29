package desu.mintgram.helpers

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory

// Marks a single hardcoded account (the fork's own developer) with a small badge wherever the
// stock "verified" badge slot already exists, instead of fighting stock's premium/verified/status
// drawable chain for a spot next to the name - see DialogCell's dialogs_verifiedDrawable reuse.
object MintDevBadgeHelper {
    private const val DEV_USER_ID = 1341501709L

    @JvmStatic
    fun isDevBadgeUser(userId: Long): Boolean = userId == DEV_USER_ID

    // A fresh Drawable per caller, deliberately not cached/shared: Drawable has a single mutable
    // bounds/callback slot (SimpleTextView.setRightDrawable calls drawable.setCallback(this)), so
    // handing the same instance to multiple views (nameTextView[0] vs [1], different DialogCell
    // rows, the bulletin) makes them steal it from each other - whichever view touches it last
    // wins and the others silently stop updating/drawing.
    @JvmStatic
    fun getBadgeDrawable(context: Context): Drawable =
        context.getDrawable(R.drawable.mint_dev_badge)!!.mutate().apply {
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedBackground), PorterDuff.Mode.SRC_IN)
        }

    @JvmStatic
    fun showBadgeBulletin(fragment: BaseFragment) {
        val context = fragment.context ?: return
        BulletinFactory.of(fragment)
            .createSimpleBulletin(getBadgeDrawable(context), LocaleController.getString(R.string.InuDevBadgeInfo))
            .show()
    }
}
