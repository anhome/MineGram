package desu.mintgram.helpers.feed

import desu.mintgram.InuConfig
import org.json.JSONArray

object FeedConfigHelper {
    fun loadExcluded(): Set<Long> {
        val json = InuConfig.FEED_EXCLUDED_CHANNELS.value
        if (json.isEmpty()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getLong(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun saveExcluded(ids: Set<Long>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        InuConfig.FEED_EXCLUDED_CHANNELS.value = arr.toString()
    }

    fun isExcluded(dialogId: Long): Boolean = dialogId in loadExcluded()

    fun setExcluded(dialogId: Long, excluded: Boolean) {
        val current = loadExcluded().toMutableSet()
        if (excluded) current.add(dialogId) else current.remove(dialogId)
        saveExcluded(current)
    }

    private fun postKey(dialogId: Long, messageId: Int) = "$dialogId:$messageId"

    fun loadExcludedPosts(): Set<String> {
        val json = InuConfig.FEED_EXCLUDED_POSTS.value
        if (json.isEmpty()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun saveExcludedPosts(keys: Set<String>) {
        val arr = JSONArray()
        for (key in keys) arr.put(key)
        InuConfig.FEED_EXCLUDED_POSTS.value = arr.toString()
    }

    fun isPostExcluded(dialogId: Long, messageId: Int): Boolean = postKey(dialogId, messageId) in loadExcludedPosts()

    @JvmStatic
    fun setPostExcluded(dialogId: Long, messageId: Int, excluded: Boolean) {
        val current = loadExcludedPosts().toMutableSet()
        val key = postKey(dialogId, messageId)
        if (excluded) current.add(key) else current.remove(key)
        saveExcludedPosts(current)
    }

    var includeArchived: Boolean
        get() = InuConfig.FEED_INCLUDE_ARCHIVED.value
        set(value) {
            InuConfig.FEED_INCLUDE_ARCHIVED.value = value
        }

    var tabEnabled: Boolean
        get() = InuConfig.FEED_TAB_ENABLED.value
        set(value) {
            InuConfig.FEED_TAB_ENABLED.value = value
        }
}
