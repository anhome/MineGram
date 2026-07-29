package desu.mintgram.helpers

import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

// Recognizes the fork's own announcement channel by username and greets the user with a
// bulletin when they open its profile - mirrors MintDevBadgeHelper's hardcoded-identity
// pattern, just for a channel instead of a user.
object OfficialChannelHelper {
    private const val CHANNEL_USERNAME = "MintGramTG"

    @JvmStatic
    fun isOfficialChannel(chat: TLRPC.Chat?): Boolean =
        chat != null && CHANNEL_USERNAME.equals(chat.username, ignoreCase = true)

    @JvmStatic
    fun showOfficialChannelBulletin(fragment: BaseFragment) {
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.InuOfficialChannelInfo))
            .show()
    }
}
