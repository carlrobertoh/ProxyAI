package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.ToolRunContext
import ee.carlrobert.codegpt.toolwindow.agent.AgentTabTitleNotifier
import kotlinx.serialization.Serializable

class TodoWriteTool(
    project: Project,
    private val sessionId: String
) : SimpleTool<TodoWriteTool.Args>(
    argsSerializer = Args.serializer(),
    name = "TodoWrite",
    description = """
Use this tool to create and manage a structured task list for your current coding session. This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.
It also helps the user understand the progress of the task and overall progress of their requests.

## When to Use This Tool
Use this tool proactively in these scenarios:

1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
3. User explicitly requests todo list - When the user directly asks you to use the todo list
4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)
5. After receiving new instructions - Immediately capture user requirements as todos
6. When you start working on a task - Mark it as in_progress BEFORE beginning work. Ideally you should only have one todo as in_progress at a time
7. After completing a task - Mark it as completed and add any new follow-up tasks discovered during implementation

## When NOT to Use This Tool

Skip using this tool when:
1. There is only a single, straightforward task
2. The task is trivial and tracking it provides no organizational benefit
3. The task can be completed in less than 3 trivial steps
4. The task is purely conversational or informational

NOTE that you should not use this tool if there is only one trivial task to do. In this case you are better off just doing the task directly.
    """.trimIndent()
) {

    private val agentTabTitlePublisher =
        project.messageBus.syncPublisher(AgentTabTitleNotifier.AGENT_TAB_TITLE_TOPIC)

    @Serializable
    data class Args(
        @property:LLMDescription("Short task title (4 words max) in imperative form (e.g., 'Fix user authentication bug')")
        val title: String,
        @property:LLMDescription("The updated todo list")
        val todos: List<TodoItem>
    )

    @Serializable
    data class TodoItem(
        @property:LLMDescription("The task description in imperative form (e.g., 'Run tests')")
        val content: String,
        @property:LLMDescription("Current status of the task: pending (not started), in_progress (currently working), or completed (finished)")
        val status: TodoStatus,
        @property:LLMDescription("The task description in present continuous form (e.g., 'Running tests')")
        val activeForm: String
    )

    @Serializable
    enum class TodoStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    override suspend fun execute(args: Args): String {
        val title = args.title
        if (title.isNotBlank()) {
            val currentContext = ToolRunContext.get(sessionId)
            val isMainAgentTodo = currentContext?.parentToolId == null

            if (isMainAgentTodo) {
                val truncatedTitle = if (title.length > 50) title.take(47) + "..." else title
                agentTabTitlePublisher.updateTabTitle(sessionId, truncatedTitle)
            }
        }

        val pendingCount = args.todos.count { it.status == TodoStatus.PENDING }
        val inProgressCount = args.todos.count { it.status == TodoStatus.IN_PROGRESS }
        val completedCount = args.todos.count { it.status == TodoStatus.COMPLETED }

        return buildString {
            appendLine("📋 Todo List Updated")
            appendLine()
            appendLine("Summary:")
            appendLine("  ⏳ Pending: $pendingCount")
            appendLine("  🔄 In Progress: $inProgressCount")
            appendLine("  ✅ Completed: $completedCount")
            appendLine()
            appendLine("Current Tasks:")
            args.todos.forEachIndexed { index, todo ->
                val statusIcon = when (todo.status) {
                    TodoStatus.PENDING -> "⏳"
                    TodoStatus.IN_PROGRESS -> "🔄"
                    TodoStatus.COMPLETED -> "✅"
                }
                val taskText = if (todo.status == TodoStatus.IN_PROGRESS) {
                    todo.activeForm
                } else {
                    todo.content
                }
                appendLine("${index + 1}. $statusIcon $taskText")
            }
        }
    }
}
