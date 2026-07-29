package desu.mintgram.helpers.ai

import desu.mintgram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

interface AiGenerationCallback {
    fun onChunk(text: String) {}
    fun onResponse(text: String)
    fun onError(code: Int, message: String)
}

/**
 * Minimal OpenAI-compatible chat-completions client (HttpURLConnection, not OkHttp — see
 * AGENT_HANDOFF plan: no networking dependency exists in the fork yet, so we stick to the one
 * precedent already in the codebase, [desu.mintgram.helpers.UrlCleanerHelper]).
 */
object AiClient {
    private val activeIds = ConcurrentHashMap.newKeySet<String>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val executor = Executors.newCachedThreadPool()

    fun isGenerating(): Boolean = activeIds.isNotEmpty()

    fun stopRequest(requestId: String) {
        activeIds.remove(requestId)
        connections.remove(requestId)?.let {
            try {
                it.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Fire-and-forget; returns a request id usable with [stopRequest]. */
    fun sendMessage(
        prompt: String,
        useHistory: Boolean,
        callback: AiGenerationCallback,
        systemPromptOverride: String? = null,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val service = AiConfigHelper.selectedService()
        if (service == null || service.key.isBlank()) {
            notifyErrorAndFinish(requestId, callback, 0, "no AI service configured")
            return requestId
        }
        activeIds.add(requestId)
        executor.execute { runRequest(requestId, service, prompt, useHistory, callback, systemPromptOverride) }
        return requestId
    }

    private fun runRequest(
        requestId: String,
        service: AiService,
        prompt: String,
        useHistory: Boolean,
        callback: AiGenerationCallback,
        systemPromptOverride: String? = null,
    ) {
        if (requestId !in activeIds) return
        val streaming = InuConfig.AI_RESPONSE_STREAMING.value
        try {
            val body = buildRequestBody(service, prompt, useHistory, streaming, systemPromptOverride)
            val conn = (URL(chatCompletionsUrl(service.url)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 5 * 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${service.key}")
            }
            if (requestId !in activeIds) {
                conn.disconnect()
                return
            }
            connections[requestId] = conn

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) {
                    null
                }
                if (requestId in activeIds) {
                    notifyErrorAndFinish(requestId, callback, code, parseErrorMessage(err, conn.responseMessage))
                }
                return
            }

            if (streaming) {
                handleStream(requestId, conn, prompt, useHistory, callback)
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val content = parseContent(text)
                if (content.isNullOrEmpty()) {
                    if (requestId in activeIds) notifyErrorAndFinish(requestId, callback, 0, "empty response")
                } else {
                    if (useHistory) appendHistory(prompt, content)
                    if (requestId in activeIds) notifyResponseAndFinish(requestId, callback, content)
                }
            }
        } catch (e: Exception) {
            if (requestId in activeIds) {
                FileLog.e(e)
                notifyErrorAndFinish(requestId, callback, 0, e.message ?: "unknown error")
            }
        } finally {
            activeIds.remove(requestId)
            connections.remove(requestId)
        }
    }

    private fun handleStream(
        requestId: String,
        conn: HttpURLConnection,
        prompt: String,
        useHistory: Boolean,
        callback: AiGenerationCallback,
    ) {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        reader.use {
            while (true) {
                if (requestId !in activeIds) return
                val line = it.readLine() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val chunk = parseStreamChunk(data) ?: continue
                if (chunk.isEmpty()) continue
                sb.append(chunk)
                if (requestId in activeIds) notifyChunk(requestId, callback, sb.toString())
            }
        }
        val result = sb.toString().trim()
        if (requestId !in activeIds) return
        if (result.isEmpty()) {
            notifyErrorAndFinish(requestId, callback, 0, "empty response")
            return
        }
        if (useHistory) appendHistory(prompt, result)
        notifyResponseAndFinish(requestId, callback, result)
    }

    private fun appendHistory(prompt: String, response: String) {
        val history = AiConfigHelper.getHistory().toMutableList()
        history.add(AiMessage("user", prompt))
        history.add(AiMessage("assistant", response))
        AiConfigHelper.saveHistory(history)
    }

    private fun chatCompletionsUrl(rawUrl: String): String {
        val base = if (rawUrl.contains("generativelanguage.googleapis")) {
            "https://generativelanguage.googleapis.com/v1beta/openai/"
        } else {
            rawUrl
        }
        return if (base.endsWith("/")) base + "chat/completions" else "$base/chat/completions"
    }

    private fun buildRequestBody(
        service: AiService,
        prompt: String,
        useHistory: Boolean,
        streaming: Boolean,
        systemPromptOverride: String? = null,
    ): JSONObject {
        val messages = JSONArray()
        val systemPrompt = systemPromptOverride ?: AiConfigHelper.selectedRole().prompt
        if (systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        if (useHistory) {
            for (m in AiConfigHelper.getHistory()) {
                messages.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        return JSONObject().apply {
            put("model", service.model)
            put("messages", messages)
            put("stream", streaming)
            put("temperature", InuConfig.AI_TEMPERATURE.value / 10.0)
            put("max_tokens", 4096)
        }
    }

    private fun parseContent(responseText: String): String? = try {
        val choices = JSONObject(responseText).optJSONArray("choices")
        val message = choices?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optJSONObject("message")
        message?.optString("content")?.takeIf { it.isNotEmpty() && message.has("content") && !message.isNull("content") }
    } catch (e: Exception) {
        FileLog.e(e)
        null
    }

    private fun parseStreamChunk(data: String): String? = try {
        val choices = JSONObject(data).optJSONArray("choices")
        val delta = choices?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optJSONObject("delta")
        delta?.opt("content")?.takeIf { it != JSONObject.NULL }?.toString()
    } catch (_: Exception) {
        null
    }

    private fun parseErrorMessage(body: String?, fallback: String?): String {
        if (!body.isNullOrEmpty()) {
            try {
                val message = JSONObject(body).optJSONObject("error")?.optString("message")
                if (!message.isNullOrEmpty()) return message
            } catch (_: Exception) {
            }
        }
        return fallback?.takeIf { it.isNotEmpty() } ?: "unknown error"
    }

    private fun notifyChunk(requestId: String, callback: AiGenerationCallback, text: String) {
        AndroidUtilities.runOnUIThread {
            if (requestId in activeIds) callback.onChunk(text)
        }
    }

    private fun notifyResponseAndFinish(requestId: String, callback: AiGenerationCallback, text: String) {
        AndroidUtilities.runOnUIThread {
            callback.onResponse(text)
        }
    }

    private fun notifyErrorAndFinish(requestId: String, callback: AiGenerationCallback, code: Int, message: String) {
        AndroidUtilities.runOnUIThread {
            callback.onError(code, message)
        }
    }
}
