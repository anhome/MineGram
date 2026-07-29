package desu.mintgram.helpers.ai

import android.content.Context
import desu.mintgram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import java.util.UUID

// Conversation history lives in its own prefs file (not InuConfig) — it's ephemeral chat state,
// not a user setting, and must never end up in the settings export/import JSON.
private val historyPrefs by lazy {
    ApplicationLoader.applicationContext.getSharedPreferences("mintgram_ai_history", Context.MODE_PRIVATE)
}

object AiConfigHelper {
    private const val HISTORY_MAX_MESSAGES = 32
    private const val HISTORY_MAX_CHARS = 24_000

    fun builtinRoles(): List<AiRole> = listOf(
        AiRole(LocaleController.getString(R.string.InuAiRoleAssistant), LocaleController.getString(R.string.InuAiRoleAssistantPrompt), builtin = true),
        AiRole(LocaleController.getString(R.string.InuAiRoleTranslator), LocaleController.getString(R.string.InuAiRoleTranslatorPrompt), builtin = true),
        AiRole(LocaleController.getString(R.string.InuAiRoleProofreader), LocaleController.getString(R.string.InuAiRoleProofreaderPrompt), builtin = true),
    )

    fun loadServices(): List<AiService> {
        val json = InuConfig.AI_SERVICES.value
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AiService(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    url = o.getString("url"),
                    model = o.getString("model"),
                    key = o.optString("key"),
                    reasoningEnabled = o.optBoolean("reasoning", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveServices(services: List<AiService>) {
        val arr = JSONArray()
        for (s in services) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("url", s.url)
                put("model", s.model)
                put("key", s.key)
                put("reasoning", s.reasoningEnabled)
            })
        }
        InuConfig.AI_SERVICES.value = arr.toString()
    }

    fun loadCustomRoles(): List<AiRole> {
        val json = InuConfig.AI_ROLES.value
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AiRole(o.getString("name"), o.getString("prompt"), builtin = false)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomRoles(roles: List<AiRole>) {
        val arr = JSONArray()
        for (r in roles) {
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("prompt", r.prompt)
            })
        }
        InuConfig.AI_ROLES.value = arr.toString()
    }

    fun allRoles(): List<AiRole> = builtinRoles() + loadCustomRoles()

    fun selectedService(): AiService? {
        val services = loadServices()
        val selectedId = InuConfig.AI_SELECTED_SERVICE_ID.value
        if (selectedId.isNotEmpty()) {
            services.firstOrNull { it.id == selectedId }?.let { return it }
        }
        return services.firstOrNull()
    }

    fun selectService(service: AiService) {
        InuConfig.AI_SELECTED_SERVICE_ID.value = service.id
        clearHistory()
    }

    fun selectedRole(): AiRole {
        val all = allRoles()
        val selectedName = InuConfig.AI_SELECTED_ROLE.value
        if (selectedName.isNotEmpty()) {
            all.firstOrNull { it.name == selectedName }?.let { return it }
        }
        return all.first()
    }

    fun selectRole(role: AiRole) {
        InuConfig.AI_SELECTED_ROLE.value = role.name
        clearHistory()
    }

    fun canUseAi(): Boolean = InuConfig.AI_ENABLED.value && selectedService()?.key?.isNotBlank() == true

    // --- conversation history (separate prefs, see [historyPrefs]) ---

    fun getHistory(): List<AiMessage> {
        val json = historyPrefs.getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AiMessage(o.getString("role"), o.getString("content"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHistory(messages: List<AiMessage>) {
        val trimmed = trimHistory(messages)
        val arr = JSONArray()
        for (m in trimmed) {
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
            })
        }
        historyPrefs.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() {
        historyPrefs.edit().remove("history").apply()
    }

    fun removeLastFromHistory() {
        val history = getHistory().toMutableList()
        if (history.size >= 2) {
            history.removeAt(history.size - 1)
            history.removeAt(history.size - 1)
            saveHistory(history)
        }
    }

    private fun trimHistory(messages: List<AiMessage>): List<AiMessage> {
        val out = ArrayDeque<AiMessage>()
        var chars = 0
        for (m in messages.asReversed()) {
            if (m.role.isEmpty() || m.content.isEmpty()) continue
            chars += m.content.length
            if (chars > HISTORY_MAX_CHARS && out.isNotEmpty()) break
            out.addFirst(m)
            if (out.size >= HISTORY_MAX_MESSAGES) break
        }
        while (out.isNotEmpty() && out.first().role == "assistant") out.removeFirst()
        return out.toList()
    }
}
