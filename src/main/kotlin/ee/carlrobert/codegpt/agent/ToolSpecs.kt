package ee.carlrobert.codegpt.agent

import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import kotlinx.serialization.builtins.serializer

enum class ToolName(val id: String, val aliases: Set<String> = emptySet()) {
    READ(ReadTool.NAME, setOf("ReadFile")),
    WRITE(WriteTool.NAME, setOf("WriteFile")),
    EDIT(EditTool.NAME, setOf("Replace", "ReplaceText")),
    BASH(BashTool.NAME, setOf("Execute", "Terminal", "RunShellCommand")),
    BASH_OUTPUT(BashOutputTool.NAME),
    KILL_SHELL(KillShellTool.NAME),
    INTELLIJ_SEARCH(
        IntelliJSearchTool.NAME,
        setOf("Search", "ListDirectory", "Glob", "Grep", "GrepSearch")
    ),
    DIAGNOSTICS(DiagnosticsTool.NAME),
    WEB_SEARCH(WebSearchTool.NAME, setOf("GoogleWebSearch")),
    WEB_FETCH(WebFetchTool.NAME),
    MCP(McpTool.NAME),
    RESOLVE_LIBRARY_ID(ResolveLibraryIdTool.NAME),
    GET_LIBRARY_DOCS(GetLibraryDocsTool.NAME),
    LOAD_SKILL(LoadSkillTool.NAME),
    TASK(TaskTool.NAME),
    ASK_USER_QUESTION(AskUserQuestionTool.NAME),
    TODO_WRITE(TodoWriteTool.NAME, setOf("TodoWriteTool")),
    EXIT("Exit");
}

data class ToolSpec<TArgs, TResult>(
    val name: ToolName,
    val argsType: TypeToken,
    val resultType: TypeToken,
    val approvalType: ToolApprovalType = ToolApprovalType.GENERIC
)

object ToolSpecs {
    fun coerceArgsForUi(toolName: String, args: Any?): Any? = args

    fun coerceResultForUi(toolName: String, result: Any?): Any? = result

    private val specsByName: Map<String, ToolSpec<*, *>> = buildMap {
        fun register(spec: ToolSpec<*, *>) {
            put(spec.name.id.lowercase(), spec)
            spec.name.aliases.forEach { alias ->
                put(alias.lowercase(), spec)
            }
        }

        register(
            ToolSpec<ReadTool.Args, ReadTool.Result>(
                ToolName.READ,
                typeToken<ReadTool.Args>(),
                typeToken<ReadTool.Result>()
            )
        )
        register(
            ToolSpec<WriteTool.Args, WriteTool.Result>(
                ToolName.WRITE,
                typeToken<WriteTool.Args>(),
                typeToken<WriteTool.Result>(),
                ToolApprovalType.WRITE
            )
        )
        register(
            ToolSpec<EditTool.Args, EditTool.Result>(
                ToolName.EDIT,
                typeToken<EditTool.Args>(),
                typeToken<EditTool.Result>(),
                ToolApprovalType.EDIT
            )
        )
        register(
            ToolSpec<BashTool.Args, BashTool.Result>(
                ToolName.BASH,
                typeToken<BashTool.Args>(),
                typeToken<BashTool.Result>(),
                ToolApprovalType.BASH
            )
        )
        register(
            ToolSpec<BashOutputTool.Args, BashOutputTool.Result>(
                ToolName.BASH_OUTPUT,
                typeToken<BashOutputTool.Args>(),
                typeToken<BashOutputTool.Result>()
            )
        )
        register(
            ToolSpec<KillShellTool.Args, KillShellTool.Result>(
                ToolName.KILL_SHELL,
                typeToken<KillShellTool.Args>(),
                typeToken<KillShellTool.Result>()
            )
        )
        register(
            ToolSpec<IntelliJSearchTool.Args, IntelliJSearchTool.Result>(
                ToolName.INTELLIJ_SEARCH,
                typeToken<IntelliJSearchTool.Args>(),
                typeToken<IntelliJSearchTool.Result>()
            )
        )
        register(
            ToolSpec<DiagnosticsTool.Args, DiagnosticsTool.Result>(
                ToolName.DIAGNOSTICS,
                typeToken<DiagnosticsTool.Args>(),
                typeToken<DiagnosticsTool.Result>()
            )
        )
        register(
            ToolSpec<WebSearchTool.Args, WebSearchTool.Result>(
                ToolName.WEB_SEARCH,
                typeToken<WebSearchTool.Args>(),
                typeToken<WebSearchTool.Result>()
            )
        )
        register(
            ToolSpec<WebFetchTool.Args, WebFetchTool.Result>(
                ToolName.WEB_FETCH,
                typeToken<WebFetchTool.Args>(),
                typeToken<WebFetchTool.Result>()
            )
        )
        register(
            ToolSpec<McpTool.Args, McpTool.Result>(
                ToolName.MCP,
                typeToken<McpTool.Args>(),
                typeToken<McpTool.Result>()
            )
        )
        register(
            ToolSpec<ResolveLibraryIdTool.Args, ResolveLibraryIdTool.Result>(
                ToolName.RESOLVE_LIBRARY_ID,
                typeToken<ResolveLibraryIdTool.Args>(),
                typeToken<ResolveLibraryIdTool.Result>()
            )
        )
        register(
            ToolSpec<GetLibraryDocsTool.Args, GetLibraryDocsTool.Result>(
                ToolName.GET_LIBRARY_DOCS,
                typeToken<GetLibraryDocsTool.Args>(),
                typeToken<GetLibraryDocsTool.Result>()
            )
        )
        register(
            ToolSpec<LoadSkillTool.Args, LoadSkillTool.Result>(
                ToolName.LOAD_SKILL,
                typeToken<LoadSkillTool.Args>(),
                typeToken<LoadSkillTool.Result>(),
                ToolApprovalType.GENERIC
            )
        )
        register(
            ToolSpec<TaskTool.Args, TaskTool.Result>(
                ToolName.TASK,
                typeToken<TaskTool.Args>(),
                typeToken<TaskTool.Result>()
            )
        )
        register(
            ToolSpec<AskUserQuestionTool.Args, AskUserQuestionTool.Result>(
                ToolName.ASK_USER_QUESTION,
                typeToken<AskUserQuestionTool.Args>(),
                typeToken<AskUserQuestionTool.Result>()
            )
        )
        register(
            ToolSpec<TodoWriteTool.Args, String>(
                ToolName.TODO_WRITE,
                typeToken<TodoWriteTool.Args>(),
                typeToken<String>()
            )
        )
        register(
            ToolSpec<Unit, Unit>(
                ToolName.EXIT,
                typeToken<Unit>(),
                typeToken<Unit>()
            )
        )
    }

    fun find(toolName: String): ToolSpec<*, *>? = specsByName[toolName.lowercase()]

    fun findName(toolName: String): ToolName? = find(toolName)?.name

    fun approvalTypeFor(toolName: String): ToolApprovalType =
        find(toolName)?.approvalType ?: ToolApprovalType.GENERIC

    fun decodeArgsOrNull(
        toolName: String,
        payload: JSONObject,
    ) = decodeElementOrNull(find(toolName)?.argsType, payload)

    fun decodeArgsOrNull(
        toolName: String,
        payload: String,
    ) = decodeStringOrNull(find(toolName)?.argsType, payload)

    fun decodeResultOrNull(
        toolName: String,
        payload: JSONElement,
    ) = decodeElementOrNull(find(toolName)?.resultType, payload)

    fun decodeResultOrNull(
        toolName: String,
        payload: String,
    ) = decodeStringOrNull(find(toolName)?.resultType, payload)

    private fun decodeElementOrNull(
        type: TypeToken?,
        rawPayload: JSONElement,
    ): Any? {
        if (type == null) {
            return null
        }

        return runCatching {
            koogJsonSerializer.decodeFromJSONElement<Any>(rawPayload, type)
        }.getOrNull()
    }

    private fun decodeStringOrNull(
        type: TypeToken?,
        rawPayload: String,
    ): Any? {
        if (type == null || rawPayload.isBlank()) {
            return null
        }
        return runCatching {
            koogJsonSerializer.decodeFromString<Any>(rawPayload, type)
        }.recoverCatching {
            val normalizedPayload = normalizeToolArgumentsJson(rawPayload) ?: throw it
            koogJsonSerializer.decodeFromString<Any>(normalizedPayload, type)
        }.getOrNull()
    }
}
