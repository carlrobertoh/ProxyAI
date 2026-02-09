package ee.carlrobert.codegpt.settings.service.custom

data class CustomServicePlaceholder(
    val name: String,
    val code: String,
    val description: String,
) {
    override fun toString(): String = code
}

object CustomServicePlaceholders {

    private const val PROMPT = $$"$PROMPT"
    private const val MESSAGES = $$"$OPENAI_MESSAGES"

    val all: List<CustomServicePlaceholder> = listOf(
        CustomServicePlaceholder(
            name = "PROMPT",
            code = PROMPT,
            description = "Replaces the placeholder with concatenated message content.",
        ),
        CustomServicePlaceholder(
            name = "MESSAGES",
            code = MESSAGES,
            description = "Replaces the placeholder with structured OpenAI format messages as a JSON array.",
        ),
    )

    private val canonicalCodes: Set<String> = all.mapTo(linkedSetOf()) { it.code }
    private val byCode: Map<String, CustomServicePlaceholder> = all.associateBy { it.code }

    fun containsCode(value: String): Boolean = value.trim() in canonicalCodes

    fun findByCode(value: String?): CustomServicePlaceholder? {
        val normalized = value?.trim() ?: return null
        return byCode[normalized]
    }

    fun isPrompt(value: String): Boolean = value.trim() == PROMPT

    fun isMessages(value: String): Boolean = value.trim() == MESSAGES
}
