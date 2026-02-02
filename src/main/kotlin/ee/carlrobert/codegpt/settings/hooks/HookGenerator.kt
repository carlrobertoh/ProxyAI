package ee.carlrobert.codegpt.settings.hooks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.executeStructured
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.AgentFactory
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.util.file.FileUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("HookGeneratorOutput")
@LLMDescription("Complete hook configuration including event type, scripts, execution configuration, and summary")
data class HookGeneratorOutput(
    @property:LLMDescription("The event type that triggers this hook (e.g., 'beforeToolUse', 'afterFileEdit')")
    val event: String,
    @property:LLMDescription("List of script files to execute as part of the hook")
    val scripts: List<HookScript>,
    @property:LLMDescription("Execution configuration for the hook")
    val config: HookConfigData,
    @property:LLMDescription("Brief description of what this hook does")
    val summary: String
)

@Serializable
@LLMDescription("A script file that can be executed as part of a hook")
data class HookScript(
    @property:LLMDescription("Filename of the script (e.g., 'analytics.sh', 'approval.py')")
    val name: String,
    @property:LLMDescription("Programming language of the script (e.g., 'sh', 'python', 'javascript')")
    val language: String,
    @property:LLMDescription("Complete executable script code with shebang and error handling")
    val content: String,
    @property:LLMDescription("Description of what the script does")
    val description: String
)

@Serializable
@LLMDescription("Configuration for executing the hook")
data class HookConfigData(
    @property:LLMDescription("Command to execute from project root")
    val command: String,
    @property:LLMDescription("Optional regex pattern for command matching")
    val matcher: String? = null,
    @property:LLMDescription("Optional timeout in seconds (5-60)")
    val timeout: Int? = null,
    @property:LLMDescription("Whether the hook is enabled")
    val enabled: Boolean = true
)

data class GeneratedHookResult(
    val event: HookEventType,
    val scripts: List<HookScript>,
    val config: HookConfig,
    val summary: String
)

object HookGenerator {
    private const val SYSTEM_PROMPT_PATH = "/prompts/hooks/hook-generator.txt"

    private val systemPrompt by lazy {
        FileUtil.getResourceContent(SYSTEM_PROMPT_PATH).trimEnd()
    }

    private val logger = thisLogger()

    suspend fun generate(query: String, project: Project? = null): GeneratedHookResult {
        return try {
            val modelService = service<ModelSelectionService>()
            val provider: ServiceType = modelService.getServiceForFeature(FeatureType.AGENT)
            val model = modelService.getAgentModel()
            val executor = AgentFactory.createExecutor(provider)
            val workspaceInfo = getWorkspaceInfo(project)
            val p = prompt("hook-generator") {
                system(
                    systemPrompt
                        .replace("{workspaceInfo}", workspaceInfo)
                        .replace("{projectPath}", project?.basePath ?: "~/.proxyai")
                        .replace("{osName}", System.getProperty("os.name"))
                )
                user(query)
            }

            val examples = HookGeneratorExamples.HOOK_GENERATION_EXAMPLES

            val structuredResponse = executor.executeStructured<HookGeneratorOutput>(
                prompt = p,
                model = model,
                examples = examples,
                fixingParser = StructureFixingParser(model, 2)
            )

            structuredResponse.getOrNull()?.data?.let { output ->
                val event =
                    HookEventType.fromString(output.event) ?: HookEventType.BEFORE_BASH_EXECUTION

                if (output.scripts.isNotEmpty()) {
                    val command = output.config.command.ifEmpty {
                        ".proxyai/hooks/${output.scripts.firstOrNull()?.name ?: "hook.sh"}"
                    }

                    val config = HookConfig(
                        command,
                        output.config.matcher,
                        output.config.timeout,
                        null,
                        output.config.enabled
                    )

                    return GeneratedHookResult(event, output.scripts, config, output.summary)
                }
            }

            fallback(query)
        } catch (e: Exception) {
            logger.warn("Exception while executing hook: ${e.message}")
            fallback(query)
        }
    }

    private fun fallback(query: String): GeneratedHookResult {
        val safe = query.trim()
        val scriptName = "custom-hook.sh"

        val content = """#!/bin/bash
# Generated hook for: $safe
# This hook tries to provide useful functionality based on your request

set -e

# Echo notification to stderr (visible in IDE terminal)
echo "Hook triggered: $safe" >&2

# Try multiple approaches based on common user requests
# Terminal bell for notification
printf '\\a' 2>/dev/null || true

# Log execution time
echo "Executed at: $(date)" >> .proxyai/hooks/execution.log 2>/dev/null || true

exit 0
"""
        val script = HookScript(scriptName, "sh", content, "Custom hook generated from: $safe")
        val config = HookConfig(".proxyai/hooks/$scriptName", null, 5, null, true)
        return GeneratedHookResult(
            HookEventType.BEFORE_BASH_EXECUTION,
            listOf(script),
            config,
            "Custom hook: $safe"
        )
    }

    fun generateBlocking(query: String, project: Project? = null): GeneratedHookResult =
        kotlinx.coroutines.runBlocking {
            generate(query, project)
        }

    private fun getWorkspaceInfo(project: Project?): String {
        if (project == null) {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            return """
            OS: $os
            Architecture: $arch
            Java: ${System.getProperty("java.version")}
            Working Directory: ~/.proxyai
            User Home: ${System.getProperty("user.home")}
            """.trimIndent()
        }

        val basePath = project.basePath ?: "Unknown"
        val projectName = project.name

        return """
        **Project Context**
        - Project Name: $projectName
        - Base Path: $basePath
        - Platform: ${System.getProperty("os.name")}
        - Architecture: ${System.getProperty("os.arch")}

        **System Context**
        - Java Version: ${System.getProperty("java.version")}
        - User Home: ${System.getProperty("user.home")}
        - Temporary Dir: ${System.getProperty("java.io.tmpdir")}
        """.trimIndent()
    }
}