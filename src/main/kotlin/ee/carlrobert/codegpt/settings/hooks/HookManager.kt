package ee.carlrobert.codegpt.settings.hooks

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HookManager(
    private val project: Project
) {
    private val logger = LoggerFactory.getLogger(HookManager::class.java)
    private val executionService = HookExecutionService()
    private val loopCounts = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun executeHooksForEvent(
        event: HookEventType,
        payload: Map<String, Any?>,
        toolName: String? = null,
        toolId: String? = null,
        sessionId: String? = null
    ): List<HookExecutionResult> {
        val settings = project.service<ProxyAISettingsService>().getSettings()
        val configuration = settings.hooks ?: return emptyList()
        val hooks = configuration.hooksFor(event)
        val matchingHooks = hooks.filter { hook ->
            hook.enabled &&
                    matcherAllows(hook, event, payload, toolName) &&
                    loopAllows(hook, event, sessionId)
        }

        if (matchingHooks.isEmpty()) {
            logger.debug("No hooks found for event: ${event.eventName}")
            return emptyList()
        }

        logger.info("Executing ${matchingHooks.size} hooks for event: ${event.eventName}")

        val results = mutableListOf<HookExecutionResult>()
        val projectRoot = project.basePath ?: System.getProperty("user.dir")

        for (hook in matchingHooks) {
            try {
                logger.debug("Executing hook '${hook.command}' for event: ${event.eventName}")
                val result = executionService.executeHook(hook, event, payload, projectRoot)
                results.add(result)
                incrementLoopCount(hook, event, sessionId)

                when (result) {
                    is HookExecutionResult.Denied -> {
                        logger.warn("Hook '${hook.command}' denied operation: ${result.reason}")
                    }

                    is HookExecutionResult.Failure -> {
                        logger.error("Hook '${hook.command}' failed: ${result.error}")
                    }

                    is HookExecutionResult.Timeout -> {
                        logger.warn("Hook '${hook.command}' timed out")
                    }

                    is HookExecutionResult.Success -> {
                        logger.debug(
                            "Hook '{}' completed successfully with output: {}",
                            hook.command,
                            result.output
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error executing hook '${hook.command}': ${e.message}", e)
                results.add(HookExecutionResult.Failure(e.message ?: "Unknown error"))
            }
        }

        return results
    }

    suspend fun checkHooksForDenial(
        event: HookEventType,
        payload: Map<String, Any?>,
        toolName: String? = null,
        toolId: String? = null,
        sessionId: String? = null
    ): String? {
        val results = executeHooksForEvent(event, payload, toolName, toolId, sessionId)
        results.forEach { result ->
            when (result) {
                is HookExecutionResult.Denied -> return result.reason
                is HookExecutionResult.Success -> {
                    if (result.output["decision"] == "deny") {
                        return result.output["reason"] as? String ?: "Hook denied execution"
                    }
                }

                else -> Unit
            }
        }
        return null
    }

    private fun matcherAllows(
        hook: HookConfig,
        event: HookEventType,
        payload: Map<String, Any?>,
        toolName: String?
    ): Boolean {
        val matcher = hook.matcher?.trim().orEmpty()
        if (matcher.isEmpty()) return true
        val target = matcherTarget(event, payload, toolName) ?: return false
        return try {
            Regex(matcher).containsMatchIn(target)
        } catch (_: Exception) {
            target.contains(matcher)
        }
    }

    private fun matcherTarget(
        event: HookEventType,
        payload: Map<String, Any?>,
        toolName: String?
    ): String? {
        return when (event) {
            HookEventType.BEFORE_TOOL_USE,
            HookEventType.AFTER_TOOL_USE -> toolName ?: payload["tool_name"]?.toString()

            HookEventType.BEFORE_BASH_EXECUTION,
            HookEventType.AFTER_BASH_EXECUTION -> payload["command"]?.toString()

            HookEventType.SUBAGENT_START,
            HookEventType.SUBAGENT_STOP -> payload["subagent_type"]?.toString()

            HookEventType.BEFORE_READ_FILE,
            HookEventType.AFTER_FILE_EDIT -> payload["file_path"]?.toString()

            HookEventType.STOP -> payload["status"]?.toString() ?: payload["reason"]?.toString()
        }
    }

    private fun loopAllows(
        hook: HookConfig,
        event: HookEventType,
        sessionId: String?
    ): Boolean {
        val limit = hook.loopLimit ?: return true
        if (event != HookEventType.STOP && event != HookEventType.SUBAGENT_STOP) return true
        val key = "${sessionId ?: "global"}:${event.eventName}:${hookIdentity(hook)}"
        val counter = loopCounts.computeIfAbsent(key) { AtomicInteger(0) }
        return counter.get() < limit
    }

    private fun incrementLoopCount(hook: HookConfig, event: HookEventType, sessionId: String?) {
        if (event != HookEventType.STOP && event != HookEventType.SUBAGENT_STOP) return
        val key = "${sessionId ?: "global"}:${event.eventName}:${hookIdentity(hook)}"
        loopCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun hookIdentity(hook: HookConfig): String {
        val base = listOf(
            hook.command,
            hook.matcher.orEmpty(),
            hook.timeout?.toString().orEmpty(),
            hook.loopLimit?.toString().orEmpty()
        ).joinToString("|")
        return base.hashCode().toString()
    }
}
