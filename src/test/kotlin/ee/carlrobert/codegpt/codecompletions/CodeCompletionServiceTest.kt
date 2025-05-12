package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession.Companion.getOrNull
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.VisualPosition
import com.intellij.testFramework.PlatformTestUtil
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_EDITOR_COMPLETION
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.llm.client.http.RequestEntity
import ee.carlrobert.llm.client.http.exchange.StreamHttpExchange
import ee.carlrobert.llm.client.util.JSONUtil.*
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest

class CodeCompletionServiceTest : IntegrationTest() {

    fun `test code completion with ProxyAI provider`() {
        useCodeGPTService()
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        val prefix = """
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             [INPUT]
             p
             """.trimIndent()
        val suffix = """
             
             [\INPUT]
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             """.trimIndent()
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/code/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prefix", "suffix", "fileExtension")
                .containsExactly("TEST_CODE_MODEL", prefix, suffix, "java")
            listOf(
                jsonMapResponse("choices", jsonArray(jsonMap("text", "ublic "))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "void"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", " main")))
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with OpenAI provider`() {
        useOpenAIService()
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        val prefix = """
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             [INPUT]
             p
             """.trimIndent()
        val suffix = """
             
             [\INPUT]
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             """.trimIndent()
        expectOpenAI(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "max_tokens")
                .containsExactly("gpt-3.5-turbo-instruct", prefix, suffix, 128)
            listOf(
                jsonMapResponse("choices", jsonArray(jsonMap("text", "ublic "))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "void"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", " main")))
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test apply next partial completion word`() {
        useLlamaService(true)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        val expectedCompletion = "public void main"
        val prefix = """
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             [INPUT]
             c
             """.trimIndent()
        val suffix = """
             
             [\INPUT]
             zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
             xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
             """.trimIndent()
        expectLlama(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/completion")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("prompt")
                .isEqualTo(
                    InfillPromptTemplate.CODE_LLAMA.buildPrompt(
                        InfillRequest.Builder(prefix, suffix, 0).build()
                    )
                )
            listOf(
                jsonMapResponse(
                    e("content", expectedCompletion),
                    e("stop", true)
                )
            )
        })
        myFixture.type('c')
        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            expectedCompletion == it
        }
        val offsetBeforeApply = myFixture.editor.caretModel.offset

        myFixture.performEditorAction(AcceptNextWordInlayAction.ID)

        assertInlineSuggestion("Failed to display next partial inline suggestion.") {
            myFixture.run {
                val appliedText =
                    editor.document.getText(TextRange(offsetBeforeApply, editor.caretModel.offset))
                "public" == appliedText && " void main" == it
            }
        }
    }

    fun `_test apply inline suggestions without initial following text`() {
        useCodeGPTService()
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "class Node {\n  "
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(1, 2))
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/code/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prefix", "suffix", "fileExtension")
                .containsExactly(
                    "TEST_CODE_MODEL",
                    "class Node {\n   ",
                    "",
                    "java"
                )
            listOf(
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n   int data;"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n   Node lef"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "t;\n   Node ri"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "ght;\n\n   public"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", " Node(int data"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", ") {\n"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "      this.data ="))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", " data;\n   }"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n}"))),
            )
        })

        myFixture.type(' ')
        assertRemainingCompletion {
            it == "int data;\n" +
                    "   Node left;\n" +
                    "   Node right;\n" +
                    "\n" +
                    "   public Node(int data) {\n" +
                    "      this.data = data;\n" +
                    "   }\n" +
                    "}"
        }
        assertInlineSuggestion {
            it == "int data;\n"
        }
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "Node left;\n" +
                    "   Node right;\n" +
                    "\n" +
                    "   public Node(int data) {\n" +
                    "      this.data = data;\n" +
                    "   }\n" +
                    "}"
        }
        assertInlineSuggestion {
            it == "Node left;\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(2, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "Node right;\n" +
                    "\n" +
                    "   public Node(int data) {\n" +
                    "      this.data = data;\n" +
                    "   }\n" +
                    "}"
        }
        assertInlineSuggestion("Failed to assert remaining completion.") {
            it == "Node right;\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(3, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "public Node(int data) {\n" +
                    "      this.data = data;\n" +
                    "   }\n" +
                    "}"
        }
        assertInlineSuggestion {
            it == "public Node(int data) {\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(5, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "this.data = data;\n" +
                    "   }\n" +
                    "}"
        }
        assertInlineSuggestion {
            it == "this.data = data;\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(6, 6))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "}\n" +
                    "}"
        }
        assertInlineSuggestion {
            it == "}\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(7, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "}"
        }
        assertInlineSuggestion {
            it == "}"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(8, 0))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == ""
        }
    }

    fun `_test apply inline suggestions with initial following text`() {
        useCodeGPTService()
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "if () {\n   \n} else {\n}"
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(0, 4))
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/code/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prefix", "suffix", "fileExtension")
                .containsExactly(
                    "TEST_CODE_MODEL",
                    "if (r",
                    ") {\n   \n} else {\n}",
                    "java"
                )
            listOf(
                jsonMapResponse("choices", jsonArray(jsonMap("text", "oot == n"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "ull) {\n"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "   root = new Node"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "(data);\n"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "   return;"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n} else {"))),
            )
        })
        myFixture.type('r')
        assertRemainingCompletion {
            it == "oot == null) {\n" +
                    "   root = new Node(data);\n" +
                    "   return;\n" +
                    "} else {"
        }
        assertInlineSuggestion {
            it == "oot == null"
        }
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "root = new Node(data);\n" +
                    "   return;\n" +
                    "} else {"
        }
        assertInlineSuggestion {
            it == "root = new Node(data);\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(1, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "return;\n" +
                    "} else {"
        }
        assertInlineSuggestion("Failed to assert remaining completion.") {
            it == "return;\n"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(2, 3))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == "} else {"
        }
        assertInlineSuggestion {
            it == "} else {"
        }
        assertThat(myFixture.editor.caretModel.visualPosition).isEqualTo(VisualPosition(3, 0))
        myFixture.type('\t')
        assertRemainingCompletion {
            it == ""
        }
    }

    fun `_test adjust completion line whitespaces`() {
        useCodeGPTService()
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "class Node {\n" +
                    "  \n" +
                    "}"
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(1, 3))
        expectCodeGPT(StreamHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/code/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prefix", "suffix", "fileExtension")
                .containsExactly(
                    "TEST_CODE_MODEL",
                    "class Node {\n   ",
                    "\n}",
                    "java"
                )
            listOf(
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n   int data;"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "\n   Node"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", " left;\n   N"))),
                jsonMapResponse("choices", jsonArray(jsonMap("text", "ode right;\n"))),
            )
        })
        myFixture.type(' ')
        assertRemainingCompletion {
            it == "int data;\n" +
                    "   Node left;\n" +
                    "   Node right;\n"
        }
        assertInlineSuggestion {
            it == "int data;\n"
        }
    }

    private fun assertRemainingCompletion(
        errorMessage: String = "Failed to assert remaining suggestion",
        onAssert: (String) -> Boolean
    ) {
        PlatformTestUtil.waitWithEventsDispatching(
            errorMessage,
            {
                val remainingCompletion = REMAINING_EDITOR_COMPLETION.get(myFixture.editor)
                    ?: return@waitWithEventsDispatching false
                onAssert(remainingCompletion)
            },
            5
        )
    }

    private fun assertInlineSuggestion(
        errorMessage: String = "Failed to assert inline suggestion",
        onAssert: (String) -> Boolean
    ) {
        PlatformTestUtil.waitWithEventsDispatching(
            errorMessage,
            {
                val session = getOrNull(myFixture.editor) ?: return@waitWithEventsDispatching false
                onAssert(session.context.textToInsert())
            },
            5
        )
    }
}