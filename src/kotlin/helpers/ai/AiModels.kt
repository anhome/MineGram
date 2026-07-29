package desu.mintgram.helpers.ai

data class AiService(
    val id: String,
    val url: String,
    val model: String,
    val key: String,
    val reasoningEnabled: Boolean = false,
) {
    val shortModel: String
        get() = model.substringAfterLast('/').substringBefore(':')
}

data class AiRole(
    val name: String,
    val prompt: String,
    val builtin: Boolean = false,
)

data class AiMessage(val role: String, val content: String)
