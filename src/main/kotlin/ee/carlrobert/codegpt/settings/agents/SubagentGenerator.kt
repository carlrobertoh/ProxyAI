package ee.carlrobert.codegpt.settings.agents

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.AgentFactory
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class SubagentGeneratorOutput(
    val title: String,
    val description: String
)

data class GeneratedSubagent(val title: String, val description: String)

object SubagentGenerator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    suspend fun generate(query: String): GeneratedSubagent {
        return try {
            val modelService = service<ModelSelectionService>()
            val provider: ServiceType = modelService.getServiceForFeature(FeatureType.AGENT)
            val model = modelService.getAgentModel()
            val executor = AgentFactory.createExecutor(provider)

            val p = prompt("subagent-generator") {
                system(
                    """
                    You are a subagent definition generator for a JetBrains IDE plugin.
                    Create concise subagent definitions from user requirements.

                    For each request, generate a valid JSON object with:
                    - "title": Name (3-6 words, sentence case)
                    - "description": Behavior summary (3-4 sentences, actionable)

                    Guidelines:
                    - Make titles concise and descriptive
                    - Use actionable, behavior-focused descriptions
                    - Avoid marketing language

                    IMPORTANT: Only output JSON. No markdown, no code fences, no explanations.
                    """.trimIndent()
                )
                user(query)
            }

            val responses = executor.execute(p, model, emptyList())
            val text = responses.filterIsInstance<Message.Assistant>()
                .joinToString("\n") { it.content.trim() }

            parseJson(text) ?: fallback(query)
        } catch (_: Exception) {
            fallback(query)
        }
    }

    private fun parseJson(text: String): GeneratedSubagent? {
        return try {
            val cleaned = extractJson(text)
            val output = json.decodeFromString<SubagentGeneratorOutput>(cleaned)

            if (output.title.isNotBlank() && output.description.isNotBlank()) {
                GeneratedSubagent(output.title, output.description)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJson(text: String): String {
        var trimmed = text.trim()

        when {
            trimmed.startsWith("```json") || trimmed.startsWith("```JSON") -> {
                trimmed = trimmed.substringAfter("```").substringAfter("\n")
                if (trimmed.endsWith("```")) {
                    trimmed = trimmed.substringBeforeLast("```")
                }
            }
            trimmed.startsWith("```") -> {
                trimmed = trimmed.substringAfter("```")
                if (trimmed.startsWith("\n")) trimmed = trimmed.substringAfter("\n")
                if (trimmed.endsWith("```")) trimmed = trimmed.substringBeforeLast("```")
            }
        }

        return trimmed.trim()
    }

    private fun fallback(query: String): GeneratedSubagent {
        val safe = query.trim()
        val title = safe.split('.', '!', '?', '\u000A')
            .firstOrNull()
            ?.take(60)
            ?.replace(Regex("\\s+"), " ")
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            ?: "Generated Subagent"
        val description = if (safe.isNotBlank()) safe else "Auto-generated subagent"
        return GeneratedSubagent(title, description)
    }

    fun generateBlocking(query: String): GeneratedSubagent = kotlinx.coroutines.runBlocking {
        generate(query)
    }
}