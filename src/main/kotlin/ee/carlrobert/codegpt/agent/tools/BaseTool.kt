package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.settings.hooks.HookEventType
import ee.carlrobert.codegpt.settings.hooks.HookEventType.BEFORE_TOOL_USE
import ee.carlrobert.codegpt.settings.hooks.HookExecutionResult
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlin.reflect.KClass

/**
 * Abstract base class for all tools that extends Koog's Tool class and
 * handles hook-based tracking logic automatically.
 *
 * @param Args The arguments type for the tool
 * @param Result The result type for the tool
 * @param workingDirectory The current working directory for hook context
 * @param hookManager Optional hook manager for executing hooks
 * @param sessionId Optional session identifier for tracking
 */
abstract class BaseTool<Args : Any, Result : Any>(
    argsSerializer: kotlinx.serialization.KSerializer<Args>,
    resultSerializer: kotlinx.serialization.KSerializer<Result>,
    name: String,
    description: String,
    protected val workingDirectory: String,
    private val hookManager: HookManager,
    private val sessionId: String? = null,
    private val argsClass: KClass<Args>,
    private val resultClass: KClass<Result>
) : Tool<Args, Result>(
    argsSerializer = argsSerializer,
    resultSerializer = resultSerializer,
    name = name,
    description = description
) {

    companion object {
        private val logger = thisLogger()
    }

    /**
     * Executes the tool with automatic hook tracking.
     *
     * This final method implements the template method pattern and cannot be
     * overridden by subclasses. It handles the full lifecycle:
     * - Applies BEFORE_TOOL_USE hooks
     * - Checks for denial
     * - Calls [doExecute] with potentially modified arguments
     * - Applies AFTER_TOOL_USE hooks
     *
     * @param args The input arguments for the tool
     * @return The result of executing the tool
     */
    final override suspend fun execute(args: Args): Result {
        val toolId = sessionId?.let { ToolRunContext.getToolId(it) }
        var effectiveArgs = args
        val beforePayload = mapOf(
            "tool_name" to name,
            "tool_input" to effectiveArgs,
            "tool_use_id" to (toolId ?: ""),
            "cwd" to workingDirectory
        )
        val beforeResults =
            hookManager.executeHooksForEvent(
                BEFORE_TOOL_USE,
                beforePayload,
                name,
                toolId,
                sessionId
            )

        for (hookResult in beforeResults) {
            when (hookResult) {
                is HookExecutionResult.Denied -> {
                    return createDeniedResult(args, hookResult.reason)
                }

                is HookExecutionResult.Failure -> {}

                is HookExecutionResult.Timeout -> {}

                is HookExecutionResult.Success -> {
                    val output = hookResult.output
                    if (output.containsKey("updated_input")) {
                        val merged =
                            mergeUpdatedInput(argsClass, effectiveArgs, output["updated_input"])
                        if (merged != null) {
                            effectiveArgs = merged
                        }
                    }
                }
            }
        }

        var effectiveResult = doExecute(effectiveArgs)

        val afterPayload = mapOf(
            "tool_name" to name,
            "tool_input" to effectiveArgs,
            "tool_output" to effectiveResult,
            "tool_use_id" to (toolId ?: ""),
            "cwd" to workingDirectory
        )
        val afterResults = hookManager.executeHooksForEvent(
            HookEventType.AFTER_TOOL_USE,
            afterPayload,
            name,
            toolId,
            sessionId
        )

        for (hookResult in afterResults) {
            when (hookResult) {
                is HookExecutionResult.Denied -> {
                    return createDeniedResult(args, hookResult.reason)
                }

                is HookExecutionResult.Failure -> {}

                is HookExecutionResult.Timeout -> {}

                is HookExecutionResult.Success -> {
                    val output = hookResult.output
                    if (output.containsKey("updated_output")) {
                        val merged =
                            mergeUpdatedInput(
                                resultClass,
                                effectiveResult,
                                output["updated_output"]
                            )
                        if (merged != null) {
                            effectiveResult = merged
                        }
                    }
                }
            }
        }

        return effectiveResult
    }

    /**
     * Executes the core logic of the tool.
     *
     * @param args The potentially modified arguments from pre-hooks
     * @return The result of executing the tool
     */
    protected abstract suspend fun doExecute(args: Args): Result

    /**
     * Creates a result for when a hook denies execution.
     *
     * Subclasses should override this to return the appropriate error result
     * type for their specific tool.
     *
     * @param originalArgs The original arguments before hook processing
     * @param deniedReason The reason why execution was denied
     * @return An error result appropriate for the tool
     */
    protected abstract fun createDeniedResult(originalArgs: Args, deniedReason: String): Result

    private fun <T : Any> mergeUpdatedInput(
        klass: KClass<T>,
        currentArgs: T,
        updatedInput: Any?
    ): T? {
        val updatedMap = updatedInput as? Map<*, *> ?: return null

        return try {
            val mapper = ObjectMapper()
                .registerKotlinModule()
                .registerModule(Jdk8Module())
                .registerModule(JavaTimeModule())

            val argsMap: MutableMap<String, Any?> = mapper.convertValue(
                currentArgs,
                mapper.typeFactory.constructMapType(
                    MutableMap::class.java,
                    String::class.java,
                    Any::class.java
                )
            )

            for ((key, value) in updatedMap) {
                val stringKey = key as? String ?: continue
                if (argsMap.containsKey(stringKey)) {
                    argsMap[stringKey] = value
                }
            }

            mapper.convertValue(argsMap, klass.java)
        } catch (_: Exception) {
            logger.warn("Failed to merge updated input $updatedInput")
            null
        }
    }
}
