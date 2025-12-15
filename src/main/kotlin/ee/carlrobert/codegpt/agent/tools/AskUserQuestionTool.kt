package ee.carlrobert.codegpt.agent.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ee.carlrobert.codegpt.agent.AgentEvents
import kotlinx.serialization.Serializable

class AskUserQuestionTool(
    private val events: AgentEvents
) : Tool<AskUserQuestionTool.Args, AskUserQuestionTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "AskUserQuestion",
    description = """
        Use this tool when you need to ask the user questions during execution. This allows you to:
        1. Gather user preferences or requirements
        2. Clarify ambiguous instructions
        3. Get decisions on implementation choices as you work
        4. Offer choices to the user about what direction to take.

        Usage notes:
        - Users will always be able to select "Other" to provide custom text input
        - Use multiSelect: true to allow multiple answers to be selected for a question
        - If you recommend a specific option, make that the first option in the list and add "(Recommended)" at the end of the label
        """.trimIndent()
) {

    @Serializable
    data class Option(
        @property:LLMDescription("The display text for this option that the user will see and select. Should be concise (1-5 words) and clearly describe the choice.")
        val label: String,
        @property:LLMDescription("Explanation of what this option means or what will happen if chosen. Useful for providing context about trade-offs or implications.")
        val description: String
    )

    @Serializable
    data class Question(
        @property:LLMDescription("The complete question to ask the user. Should be clear, specific, and end with a question mark. Example: \"Which library should we use for date formatting?\" If multiSelect is true, phrase it accordingly, e.g. \"Which features do you want to enable?\"")
        val question: String,
        @property:LLMDescription("Very short label displayed as a chip/tag (max 12 chars). Examples: \"Auth method\", \"Library\", \"Approach\".")
        val header: String,
        @property:LLMDescription("The available choices for this question. Must have 2-4 options. Each option should be a distinct, mutually exclusive choice (unless multiSelect is enabled). There should be no 'Other' option, that will be provided automatically.")
        val options: List<Option>,
        @property:LLMDescription("Set to true to allow the user to select multiple options instead of just one. Use when choices are not mutually exclusive.")
        val multiSelect: Boolean
    )

    @Serializable
    data class Args(
        @property:LLMDescription("Questions to ask the user (1-4 questions)")
        val questions: List<Question>,
        @property:LLMDescription("User answers collected by the permission component")
        val answers: Map<String, String>? = null
    )

    @Serializable
    sealed class Result {
        @Serializable
        data class Success(
            val answers: Map<String, String>
        ) : Result()

        @Serializable
        data class Error(
            val message: String
        ) : Result()
    }

    override suspend fun execute(args: Args): Result {
        if (args.questions.isEmpty()) {
            return Result.Error("At least one question is required")
        }
        if (args.questions.size > 4) {
            return Result.Error("Maximum of 4 questions allowed")
        }
        args.questions.forEach { q ->
            if (q.options.size !in 2..4) return Result.Error("Question '${q.header}' must have 2-4 options")
        }

        val collected = try {
            events.askUserQuestions(
                AskUserQuestionsModel(
                    questions = args.questions,
                    prefilledAnswers = args.answers ?: emptyMap()
                )
            )
        } catch (e: Exception) {
            return Result.Error(e.message ?: "Question flow cancelled or failed")
        }

        return Result.Success(collected)
    }

    override fun encodeResultToString(result: Result): String = when (result) {
        is Result.Success -> buildString {
            append("User answered:\n")
            result.answers.forEach { (k, v) -> append("- ").append(k).append(": ").append(v).append('\n') }
        }.trimEnd()
        is Result.Error -> "AskUserQuestion failed: ${result.message}"
    }

    @Serializable
    data class AskUserQuestionsModel(
        val questions: List<Question>,
        val prefilledAnswers: Map<String, String> = emptyMap()
    )
}
