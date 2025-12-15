package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.tools.ToolRegistry
import ee.carlrobert.codegpt.agent.tools.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object ToolCallPayloadDecoder {

    private val fallbackJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        decodeEnumsCaseInsensitive = true
    }

    fun decodeArgs(toolRegistry: ToolRegistry?, toolName: String, rawArgs: JsonObject): Any? {
        val tool = toolRegistry?.getToolOrNull(toolName)
        if (tool != null) {
            return runCatching { tool.decodeArgs(rawArgs) }.getOrElse { rawArgs }
        }
        val serializer = argsSerializerFor(toolName) ?: return rawArgs
        return decodeWith(serializer, rawArgs, rawArgs)
    }

    fun decodeResult(toolRegistry: ToolRegistry?, toolName: String, rawResult: JsonElement?): Any? {
        if (rawResult == null) return null
        val tool = toolRegistry?.getToolOrNull(toolName)
        if (tool != null) {
            return runCatching { tool.decodeResult(rawResult) }.getOrElse { rawResult }
        }
        val serializer = resultSerializerFor(toolName) ?: return rawResult
        return decodeWith(serializer, rawResult, rawResult)
    }

    private fun argsSerializerFor(toolName: String): KSerializer<out Any>? {
        return when (toolName) {
            "Read" -> ReadTool.Args.serializer()
            "Write" -> WriteTool.Args.serializer()
            "Edit" -> EditTool.Args.serializer()
            "Bash" -> BashTool.Args.serializer()
            "BashOutput" -> BashOutputTool.Args.serializer()
            "KillShell" -> KillShellTool.Args.serializer()
            "IntelliJSearch" -> IntelliJSearchTool.Args.serializer()
            "WebSearch" -> WebSearchTool.Args.serializer()
            "ResolveLibraryId" -> ResolveLibraryIdTool.Args.serializer()
            "GetLibraryDocs" -> GetLibraryDocsTool.Args.serializer()
            "Task" -> TaskTool.Args.serializer()
            "AskUserQuestion" -> AskUserQuestionTool.Args.serializer()
            "TodoWrite", "TodoWriteTool" -> TodoWriteTool.Args.serializer()
            "Exit" -> Unit.serializer()
            else -> null
        }
    }

    private fun resultSerializerFor(toolName: String): KSerializer<out Any>? {
        return when (toolName) {
            "Read" -> ReadTool.Result.serializer()
            "Write" -> WriteTool.Result.serializer()
            "Edit" -> EditTool.Result.serializer()
            "Bash" -> BashTool.Result.serializer()
            "BashOutput" -> BashOutputTool.Result.serializer()
            "KillShell" -> KillShellTool.Result.serializer()
            "IntelliJSearch" -> IntelliJSearchTool.Result.serializer()
            "WebSearch" -> WebSearchTool.Result.serializer()
            "ResolveLibraryId" -> ResolveLibraryIdTool.Result.serializer()
            "GetLibraryDocs" -> GetLibraryDocsTool.Result.serializer()
            "Task" -> TaskTool.Result.serializer()
            "AskUserQuestion" -> AskUserQuestionTool.Result.serializer()
            "TodoWrite", "TodoWriteTool" -> String.serializer()
            "Exit" -> Unit.serializer()
            else -> null
        }
    }

    private fun <T> decodeWith(
        serializer: KSerializer<T>,
        raw: JsonElement,
        fallback: Any?
    ): Any? {
        return runCatching { fallbackJson.decodeFromJsonElement(serializer, raw) }.getOrElse { fallback }
    }
}
