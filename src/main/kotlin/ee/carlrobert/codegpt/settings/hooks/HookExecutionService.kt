package ee.carlrobert.codegpt.settings.hooks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HookExecutionService {
    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(Jdk8Module())
        .registerModule(JavaTimeModule())
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
            val process =
                buildCommandLine(hookConfig.command, environment, projectRoot).createProcess()

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

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
                .use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
                .use { it.readText() }

            if (stdout.isNotBlank()) {
                logger.debug("Hook '${hookConfig.command}' stdout: ${truncateForLog(stdout)}")
            }
            if (stderr.isNotBlank()) {
                logger.debug("Hook '${hookConfig.command}' stderr: ${truncateForLog(stderr)}")
            }

            when (val exitCode = process.exitValue()) {
                0 -> {
                    val tree = parseJsonNodeOrNull(stdout)
                    if (tree != null) {
                        HookExecutionResult.Success(responseAsMap(tree))
                    } else {
                        HookExecutionResult.Success(emptyMap())
                    }
                }

                2 -> {
                    val reason = parseJsonNodeOrNull(stdout)?.path("reason")?.asText()
                        ?.takeIf { it.isNotBlank() }
                        ?: stdout.trim().takeIf { it.isNotBlank() }
                        ?: "Hook denied execution"
                    logger.info("Hook '${hookConfig.command}' denied operation: $reason")
                    HookExecutionResult.Denied(reason)
                }

                else -> {
                    val error = listOf(stdout.trim(), stderr.trim())
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                        .ifBlank { "Hook failed with exit code $exitCode" }
                    logger.error("Hook '${hookConfig.command}' failed with exit code $exitCode: $error")
                    HookExecutionResult.Failure(error)
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

    private fun buildCommandLine(
        command: String,
        environment: Map<String, String>,
        projectRoot: String
    ): GeneralCommandLine {
        val shellCommand = shellCommand(command)
        return GeneralCommandLine().apply {
            exePath = shellCommand.first()
            addParameters(shellCommand.drop(1))
            withWorkDirectory(File(projectRoot))
            withEnvironment(environment)
            isRedirectErrorStream = false
        }
    }

    private fun buildEnvironment(event: HookEventType, projectRoot: String): Map<String, String> {
        return mapOf(
            "PROXYAI_PROJECT_DIR" to projectRoot,
            "PROXYAI_HOOK_EVENT" to event.eventName
        )
    }

    private fun parseJsonNodeOrNull(raw: String): JsonNode? {
        if (raw.isBlank()) return null
        val normalized = raw.dropWhile { ch ->
            ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t'
        }.trimStart()
        val first = normalized.firstOrNull() ?: return null
        if (first != '{' && first != '[') return null
        return runCatching { objectMapper.readTree(normalized) }.getOrNull()
    }

    private fun truncateForLog(value: String, maxLen: Int = 500): String {
        val trimmed = value.trim()
        return StringUtil.shortenTextWithEllipsis(trimmed, maxLen, 0)
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
