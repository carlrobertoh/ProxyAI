package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession.Companion.getOrNull
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_EDITOR_COMPLETION
import ee.carlrobert.codegpt.completions.HuggingFaceModel
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CustomServiceApiKeyById
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.util.file.FileUtil
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import testsupport.http.RequestEntity
import testsupport.http.ResponseEntity
import testsupport.http.exchange.BasicHttpExchange
import testsupport.http.exchange.StreamHttpExchange
import testsupport.json.JSONUtil.e
import testsupport.json.JSONUtil.jsonArray
import testsupport.json.JSONUtil.jsonMap
import testsupport.json.JSONUtil.jsonMapResponse
import ee.carlrobert.service.CodeCompletionServiceImplGrpc
import ee.carlrobert.service.GrpcCodeCompletionRequest
import ee.carlrobert.service.PartialCodeCompletionResponse
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver

class CodeCompletionServiceTest : IntegrationTest() {

    private var proxyAiGrpcServer: Server? = null

    override fun tearDown() {
        proxyAiGrpcServer?.shutdownNow()
        proxyAiGrpcServer = null
        System.clearProperty("proxyai.grpc.host")
        System.clearProperty("proxyai.grpc.port")
        System.clearProperty("proxyai.grpc.plaintext")
        super.tearDown()
    }

    fun `test code completion with OpenAI provider`() {
        useOpenAIService("gpt-4", FeatureType.CODE_COMPLETION)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.txt",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        project.service<CodeCompletionCacheService>().clear()
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
        expectOpenAI(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "max_tokens", "stream")
                .containsExactly("gpt-3.5-turbo-instruct", prefix, suffix, 128, false)
            ResponseEntity(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("text", "ublic void main"))
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with Ollama provider and separate model settings`() {
        useOllamaService(FeatureType.CODE_COMPLETION)
        myFixture.configureByText(
            "CompletionTest.txt",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        project.service<CodeCompletionCacheService>().clear()
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
        expectOllama(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/api/generate")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "stream")
                .containsExactly(HuggingFaceModel.CODE_QWEN_2_5_3B_Q4_K_M.code, prefix, suffix, false)
            ResponseEntity(
                jsonMapResponse(
                    e("model", HuggingFaceModel.CODE_QWEN_2_5_3B_Q4_K_M.code),
                    e("created_at", "2023-08-04T08:52:19.385406455-07:00"),
                    e("response", "rivate void main"),
                    e("done", true),
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "rivate void main" == it
        }
    }

    fun `test code completion with custom openai provider uses completion endpoint`() {
        val customService = CustomServiceSettingsState().apply {
            name = "Completion Test Custom Service"
            codeCompletionSettings.url = System.getProperty("customOpenAI.baseUrl") + "/v1/completions"
            codeCompletionSettings.headers.clear()
            codeCompletionSettings.headers["Authorization"] = "Bearer \$CUSTOM_SERVICE_API_KEY"
            codeCompletionSettings.body.clear()
            codeCompletionSettings.body["stream"] = true
            codeCompletionSettings.body["prompt"] = "\$PREFIX"
            codeCompletionSettings.body["suffix"] = "\$SUFFIX"
            codeCompletionSettings.body["model"] = "custom-code-model"
            codeCompletionSettings.body["max_tokens"] = 128
        }
        service<CustomServicesSettings>().state.services.clear()
        service<CustomServicesSettings>().state.services.add(customService)
        setCredential(CustomServiceApiKeyById(requireNotNull(customService.id)), "TEST_API_KEY")
        service<ModelSettings>().setModel(
            FeatureType.CODE_COMPLETION,
            customService.id,
            ServiceType.CUSTOM_OPENAI
        )

        myFixture.configureByText(
            "CompletionTest.txt",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        project.service<CodeCompletionCacheService>().clear()
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
        expectCustomOpenAI(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "max_tokens", "stream")
                .containsExactly("custom-code-model", prefix, suffix, 128, false)
            ResponseEntity(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("text", "ublic void main"))
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with Mistral provider`() {
        useMistralService(FeatureType.CODE_COMPLETION)
        val fixture = prepareStandardCompletionFixture()
        expectMistral(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/fim/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "max_tokens", "stream")
                .containsExactly("codestral-latest", fixture.prefix, fixture.suffix, 128, false)
            ResponseEntity(
                jsonMapResponse(
                    "choices",
                    jsonArray(
                        jsonMap(
                            "message",
                            jsonMap("content", "ublic void main")
                        )
                    )
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with Inception provider`() {
        useInceptionService(FeatureType.CODE_COMPLETION)
        val fixture = prepareStandardCompletionFixture()
        expectInception(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/v1/fim/completions")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("model", "prompt", "suffix", "stream")
                .containsExactly("mercury-edit-2", fixture.prefix, fixture.suffix, false)
            ResponseEntity(
                jsonMapResponse(
                    "choices",
                    jsonArray(jsonMap("text", "ublic void main"))
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with llama cpp provider`() {
        useLlamaService(codeCompletionsEnabled = true, role = FeatureType.CODE_COMPLETION)
        val fixture = prepareStandardCompletionFixture()
        val prompt = LlamaModel.findByHuggingFaceModel(HuggingFaceModel.CODE_LLAMA_7B_Q4)
            .infillPromptTemplate
            .buildPrompt(InfillRequest.Builder(fixture.prefix, fixture.suffix, 0).build())
        expectLlama(BasicHttpExchange { request: RequestEntity ->
            assertThat(request.uri.path).isEqualTo("/completion")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.body)
                .extracting("prompt", "stream", "n_predict")
                .containsExactly(prompt, false, 128)
            ResponseEntity(
                jsonMapResponse(
                    "content",
                    "ublic void main"
                )
            )
        })

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `test code completion with ProxyAI provider`() {
        useCodeGPTService(FeatureType.CODE_COMPLETION)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        val fixture = prepareStandardCompletionFixture()
        startProxyAiGrpcServer(fixture)

        myFixture.type('p')

        assertInlineSuggestion("Failed to display initial inline suggestion.") {
            "ublic void main" == it
        }
    }

    fun `_test apply inline suggestions without initial following text`() {
        useCodeGPTService(FeatureType.CODE_COMPLETION)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "class Node {\n  "
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(1, 2))
        project.service<CodeCompletionCacheService>().clear()
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
        useCodeGPTService(FeatureType.CODE_COMPLETION)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "if () {\n   \n} else {\n}"
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(0, 4))
        project.service<CodeCompletionCacheService>().clear()
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
        useCodeGPTService(FeatureType.CODE_COMPLETION)
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.java",
            "class Node {\n" +
                    "  \n" +
                    "}"
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(1, 3))
        project.service<CodeCompletionCacheService>().clear()
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

    private fun prepareStandardCompletionFixture(): CompletionFixture {
        service<CodeGPTServiceSettings>().state.nextEditsEnabled = false
        myFixture.configureByText(
            "CompletionTest.txt",
            FileUtil.getResourceContent("/codecompletions/code-completion-file.txt")
        )
        myFixture.editor.caretModel.moveToVisualPosition(VisualPosition(3, 0))
        project.service<CodeCompletionCacheService>().clear()
        return CompletionFixture(
            prefix = """
                 xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
                 zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
                 [INPUT]
                 p
                 """.trimIndent(),
            suffix = """
                 
                 [\INPUT]
                 zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz
                 xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
                 """.trimIndent()
        )
    }

    private data class CompletionFixture(
        val prefix: String,
        val suffix: String
    )

    private fun startProxyAiGrpcServer(fixture: CompletionFixture) {
        val service = object : CodeCompletionServiceImplGrpc.CodeCompletionServiceImplImplBase() {
            override fun getCodeCompletion(
                request: GrpcCodeCompletionRequest,
                responseObserver: StreamObserver<PartialCodeCompletionResponse>
            ) {
                assertThat(request.model).isEqualTo("mercury-edit-2")
                assertThat(request.fileContent).contains(fixture.prefix)
                assertThat(request.cursorPosition).isGreaterThan(0)
                responseObserver.onNext(
                    PartialCodeCompletionResponse.newBuilder()
                        .setId("test-response-id")
                        .setPartialCompletion("ublic void main")
                        .build()
                )
                responseObserver.onCompleted()
            }
        }

        proxyAiGrpcServer = ServerBuilder.forPort(0)
            .addService(service)
            .build()
            .start()

        System.setProperty("proxyai.grpc.host", "127.0.0.1")
        System.setProperty("proxyai.grpc.port", proxyAiGrpcServer!!.port.toString())
        System.setProperty("proxyai.grpc.plaintext", "true")
    }
}
