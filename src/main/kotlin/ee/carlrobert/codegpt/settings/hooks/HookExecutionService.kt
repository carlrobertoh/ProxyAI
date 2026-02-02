package ee.carlrobert.codegpt.settings.hooks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HookExecutionService {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val logger = LoggerFactory.getLogger(HookExecutionService::class.java)

    suspend fun executeHook(
        hookConfig: HookConfig,
        event: HookEventType,
        payload: Map<String, Any?>,
        projectRoot: String
    ): HookExecutionResult = withContext(Dispatchers.IO) {
        val environment = buildEnvironment(event, projectRoot)
        val enrichedPayload = payload.toMutableMap().apply {
            if (!containsKey("hook_event_name")) {
                this["hook_event_name"] = event.eventName
            }
        }

        try {
            val command = shellCommand(hookConfig.command)
            val processBuilder = ProcessBuilder(command)

            environment.forEach { (key, value) ->
                processBuilder.environment()[key] = value
            }

            val process = processBuilder
                .directory(File(projectRoot))
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                try {
                    objectMapper.writeValue(writer, enrichedPayload)
                    writer.newLine()
                } catch (e: Exception) {
                    logger.debug("Hook '${hookConfig.command}' stdin write failed", e)
                }
            }

            val timeoutMs = hookConfig.timeout?.let { it * 1000L } ?: 30000L
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                logger.warn("Hook '${hookConfig.command}' timed out after ${timeoutMs}ms")
                return@withContext HookExecutionResult.Timeout
            }

            val output = BufferedReader(InputStreamReader(process.inputStream))
                .use { it.readText() }

            logger.debug("Hook '${hookConfig.command}' output: $output")

            when (val exitCode = process.exitValue()) {
                0 -> {
                    val response = try {
                        val tree = objectMapper.readTree(output)
                        HookExecutionResult.Success(responseAsMap(tree))
                    } catch (e: Exception) {
                        logger.warn("Hook '${hookConfig.command}' did not return valid JSON, treating as empty output", e)
                        HookExecutionResult.Success(emptyMap())
                    }
                    response
                }
                2 -> {
                    val reason = try {
                        val tree = objectMapper.readTree(output)
                        tree.path("reason").asText()
                    } catch (e: Exception) {
                        logger.warn("Hook '${hookConfig.command}' did not return valid JSON, reason: $e")
                        "Hook denied execution"
                    }
                    logger.info("Hook '${hookConfig.command}' denied operation: $reason")
                    HookExecutionResult.Denied(reason)
                }
                else -> {
                    logger.error("Hook '${hookConfig.command}' failed with exit code $exitCode: $output")
                    HookExecutionResult.Failure(output)
                }
            }
        } catch (e: TimeoutException) {
            logger.warn("Hook '${hookConfig.command}' timed out due to exception", e)
            HookExecutionResult.Timeout
        } catch (e: Exception) {
            logger.error("Hook '${hookConfig.command}' execution failed", e)
            HookExecutionResult.Failure(e.message ?: "Hook execution failed")
        }
    }

    private fun shellCommand(command: String): List<String> {
        return when {
            System.getProperty("os.name").startsWith("Windows") ->
                listOf("cmd.exe", "/c", command)
            else -> listOf("sh", "-c", command)
        }
    }

    private fun buildEnvironment(event: HookEventType, projectRoot: String): Map<String, String> {
        return mapOf(
            "PROXYAI_PROJECT_DIR" to projectRoot,
            "PROXYAI_HOOK_EVENT" to event.eventName
        )
    }

    private fun responseAsMap(node: JsonNode): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        node.fields().forEach { (key, value) ->
            result[key] = when {
                value.isTextual -> value.asText()
                value.isBoolean -> value.asBoolean()
                value.isInt -> value.asInt()
                value.isLong -> value.asLong()
                value.isObject -> responseAsMap(value)
                value.isArray -> {
                    value.map { node ->
                        when {
                            node.isTextual -> node.asText()
                            node.isBoolean -> node.asBoolean()
                            node.isInt -> node.asInt()
                            node.isLong -> node.asLong()
                            node.isObject -> responseAsMap(node)
                            else -> node.toString()
                        }
                    }
                }
                else -> value.toString()
            }
        }
        return result
    }
}

sealed class HookExecutionResult {
    data class Success(val output: Map<String, Any>) : HookExecutionResult()
    data class Denied(val reason: String) : HookExecutionResult()
    data class Failure(val error: String) : HookExecutionResult()
    object Timeout : HookExecutionResult()
}
