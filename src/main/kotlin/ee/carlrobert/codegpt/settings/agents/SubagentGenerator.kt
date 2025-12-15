package ee.carlrobert.codegpt.settings.agents

import ai.koog.prompt.message.Message
import ai.koog.prompt.dsl.prompt
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.agent.AgentFactory
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType

data class GeneratedSubagent(val title: String, val description: String)

object SubagentGenerator {
    suspend fun generate(query: String): GeneratedSubagent {
        return try {
            val modelService = service<ModelSelectionService>()
            val provider: ServiceType = modelService.getServiceForFeature(FeatureType.AGENT)
            val model = modelService.getAgentModel()
            val executor = AgentFactory.createExecutor(provider)

            val p = prompt("subagent-generator") {
                system(
                    """
                    You generate concise subagent definitions for a JetBrains plugin.
                    Output only a single compact JSON object with keys: "title" and "description".
                    - title: 3-6 words, concise, sentence case
                    - description: 3-4 sentences, actionable behavior summary
                    No prose, no markdown, no code fences.
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
            val mapper = ObjectMapper().registerKotlinModule()
            val trimmed = text.trim().trim('`')
            val node = mapper.readTree(trimmed)
            val title = node.get("title")?.asText()?.trim().orEmpty()
            val description = node.get("description")?.asText()?.trim().orEmpty()
            if (title.isNotBlank() && description.isNotBlank()) GeneratedSubagent(title, description) else null
        } catch (_: Exception) {
            null
        }
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
