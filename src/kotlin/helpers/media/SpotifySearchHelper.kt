package desu.mintgram.helpers.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R

object SpotifySearchHelper {
    @JvmStatic
    fun open(context: Context, message: MessageObject): Boolean {
        val title = message.getMusicTitle(false)?.trim().orEmpty()
        val artist = message.getMusicAuthor(false)?.trim().orEmpty()
        val query = listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" ")
        if (query.isEmpty()) {
            Toast.makeText(
                context,
                LocaleController.getString(R.string.InuSpotifySearchUnavailable),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }

        val uri = Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse {
            Toast.makeText(
                context,
                LocaleController.getString(R.string.InuSpotifySearchFailed),
                Toast.LENGTH_SHORT,
            ).show()
            false
        }
    }
}
