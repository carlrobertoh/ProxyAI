package ee.carlrobert.codegpt.agent

import ee.carlrobert.codegpt.agent.tools.*
import ee.carlrobert.codegpt.toolwindow.agent.ui.approval.ToolApprovalType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

enum class ToolName(val id: String, val aliases: Set<String> = emptySet()) {
    READ("Read"),
    WRITE("Write"),
    EDIT("Edit"),
    BASH("Bash"),
    BASH_OUTPUT("BashOutput"),
    KILL_SHELL("KillShell"),
    INTELLIJ_SEARCH("IntelliJSearch"),
    WEB_SEARCH("WebSearch"),
    RESOLVE_LIBRARY_ID("ResolveLibraryId"),
    GET_LIBRARY_DOCS("GetLibraryDocs"),
    LOAD_SKILL("LoadSkill"),
    TASK("Task"),
    ASK_USER_QUESTION("AskUserQuestion"),
    TODO_WRITE("TodoWrite", setOf("TodoWriteTool")),
    EXIT("Exit");

    companion object {
        val HOOK_AWARE = setOf(
            READ,
            WRITE,
            EDIT,
            BASH,
            TASK,
            INTELLIJ_SEARCH,
            WEB_SEARCH,
            RESOLVE_LIBRARY_ID,
            GET_LIBRARY_DOCS,
            LOAD_SKILL,
            ASK_USER_QUESTION,
            TODO_WRITE,
            BASH_OUTPUT,
            KILL_SHELL
        )

        fun isHookAware(toolId: String): Boolean {
            return fromId(toolId) in HOOK_AWARE
        }

        fun fromId(toolId: String): ToolName? {
            return entries.find { it.id == toolId }
        }
    }
}

data class ToolSpec<TArgs, TResult>(
    val name: ToolName,
    val argsSerializer: KSerializer<TArgs>,
    val resultSerializer: KSerializer<TResult>,
    val approvalType: ToolApprovalType = ToolApprovalType.GENERIC
)

object ToolSpecs {
    private val specsByName: Map<String, ToolSpec<*, *>> = buildMap {
        fun register(spec: ToolSpec<*, *>) {
            put(spec.name.id.lowercase(), spec)
            spec.name.aliases.forEach { alias ->
                put(alias.lowercase(), spec)
            }
        }

        register(
            ToolSpec(
                ToolName.READ,
                ReadTool.Args.serializer(),
                ReadTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.WRITE,
                WriteTool.Args.serializer(),
                WriteTool.Result.serializer(),
                ToolApprovalType.WRITE
            )
        )
        register(
            ToolSpec(
                ToolName.EDIT,
                EditTool.Args.serializer(),
                EditTool.Result.serializer(),
                ToolApprovalType.EDIT
            )
        )
        register(
            ToolSpec(
                ToolName.BASH,
                BashTool.Args.serializer(),
                BashTool.Result.serializer(),
                ToolApprovalType.BASH
            )
        )
        register(
            ToolSpec(
                ToolName.BASH_OUTPUT,
                BashOutputTool.Args.serializer(),
                BashOutputTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.KILL_SHELL,
                KillShellTool.Args.serializer(),
                KillShellTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.INTELLIJ_SEARCH,
                IntelliJSearchTool.Args.serializer(),
                IntelliJSearchTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.WEB_SEARCH,
                WebSearchTool.Args.serializer(),
                WebSearchTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.RESOLVE_LIBRARY_ID,
                ResolveLibraryIdTool.Args.serializer(),
                ResolveLibraryIdTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.GET_LIBRARY_DOCS,
                GetLibraryDocsTool.Args.serializer(),
                GetLibraryDocsTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.LOAD_SKILL,
                LoadSkillTool.Args.serializer(),
                LoadSkillTool.Result.serializer(),
                ToolApprovalType.GENERIC
            )
        )
        register(
            ToolSpec(
                ToolName.TASK,
                TaskTool.Args.serializer(),
                TaskTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.ASK_USER_QUESTION,
                AskUserQuestionTool.Args.serializer(),
                AskUserQuestionTool.Result.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.TODO_WRITE,
                TodoWriteTool.Args.serializer(),
                String.serializer()
            )
        )
        register(
            ToolSpec(
                ToolName.EXIT,
                Unit.serializer(),
                Unit.serializer()
            )
        )
    }

    fun find(toolName: String): ToolSpec<*, *>? = specsByName[toolName.lowercase()]

    fun approvalTypeFor(toolName: String): ToolApprovalType {
        return find(toolName)?.approvalType ?: ToolApprovalType.GENERIC
    }
}
