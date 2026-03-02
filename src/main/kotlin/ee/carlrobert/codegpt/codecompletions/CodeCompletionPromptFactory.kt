package ee.carlrobert.codegpt.codecompletions

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams

internal object CodeCompletionPromptFactory {

    private const val MAX_TOKENS = 128
    private const val SYSTEM_PROMPT = "You are a code completion assistant. " +
            "Complete the code between the given prefix and suffix. " +
            "Return only the missing code that should be inserted, without any formatting, explanations, or markdown."

    fun createPrompt(infillRequest: InfillRequest): Prompt {
        val params = LLMParams(
            maxTokens = MAX_TOKENS,
            temperature = 0.0
        )

        return prompt("code-completion-fim", params = params) {
            system(SYSTEM_PROMPT)
            user(buildFIMUserPrompt(infillRequest))
        }
    }

    private fun buildFIMUserPrompt(infillRequest: InfillRequest): String {
        return "<PREFIX>\n${infillRequest.prefix}\n</PREFIX>\n\n" +
                "<SUFFIX>\n${infillRequest.suffix}\n</SUFFIX>\n\n" +
                "Complete:"
    }
}
