package ee.carlrobert.codegpt.agent.strategy

import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class SingleRunStrategyProviderTest {

    @Test
    fun `extract assistant output prefers assistant text when available`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("Final answer", ResponseMetaInfo.Empty)
        )

        val actual = extractAssistantOutput(responses)

        assertThat(actual).isEqualTo("Final answer")
    }

    @Test
    fun `extract assistant output falls back to reasoning when assistant text is blank`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("", ResponseMetaInfo.Empty)
        )

        val actual = extractAssistantOutput(responses)

        assertThat(actual).isEqualTo("<think>Reasoning text</think>")
    }

    @Test
    fun `appendable responses keeps reasoning fallback for non google when assistant is blank`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("", ResponseMetaInfo.Empty)
        )

        val actual = appendableResponses(responses, LLMProvider.OpenAI)

        assertThat(actual)
            .singleElement()
            .isInstanceOf(Message.Reasoning::class.java)
    }

    @Test
    fun `appendable responses removes reasoning for non google when assistant has text`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("Final answer", ResponseMetaInfo.Empty)
        )

        val actual = appendableResponses(responses, LLMProvider.OpenAI)

        assertThat(actual)
            .singleElement()
            .isInstanceOf(Message.Assistant::class.java)
    }

    @Test
    fun `appendable responses keeps reasoning for google`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("Final answer", ResponseMetaInfo.Empty)
        )

        val actual = appendableResponses(responses, LLMProvider.Google)

        assertThat(actual.map { it::class.simpleName })
            .containsExactly("Assistant", "Reasoning")
    }

    @Test
    fun `appendable responses drops assistant text for google tool turns`() {
        val responses = listOf(
            Message.Reasoning(content = "Reasoning text", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("I will inspect the file.", ResponseMetaInfo.Empty),
            Message.Tool.Call(
                id = "tool-1",
                tool = "Read",
                content = """{"path":"build.gradle.kts"}""",
                metaInfo = ResponseMetaInfo.Empty
            )
        )

        val actual = appendableResponses(responses, LLMProvider.Google)
        val toolCall = actual.filterIsInstance<Message.Tool.Call>().single()

        assertThat(actual.map { it::class.simpleName })
            .containsExactly("Reasoning", "Call")
        assertThat(toolCall.tool).isEqualTo("Read")
        assertThat(toolCall.content).isEqualTo("""{"path":"build.gradle.kts"}""")
    }
}
