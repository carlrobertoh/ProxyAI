package ee.carlrobert.codegpt.codecompletions

import ee.carlrobert.codegpt.psistructure.ClassStructureSerializer
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty

enum class InfillPromptTemplate(val label: String, val stopTokens: List<String>? = listOf("\n\n")) {

    OPENAI("OpenAI") {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            val infillPrompt =
                "<|fim_prefix|> ${infillDetails.prefix} <|fim_suffix|>${infillDetails.suffix} <|fim_middle|>"
            return createDefaultMultiFilePrompt(infillDetails, infillPrompt)
        }
    },
    CODE_LLAMA("Code Llama", listOf("<EOT>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            val infillPrompt = "<PRE> ${infillDetails.prefix} <SUF>${infillDetails.suffix} <MID>"
            return createDefaultMultiFilePrompt(infillDetails, infillPrompt)
        }
    },
    CODE_GEMMA("CodeGemma Instruct") {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            // see https://huggingface.co/google/codegemma-7b#for-code-completion
            val infillPrompt =
                "<|fim_prefix|>${infillDetails.prefix}<|fim_suffix|>${infillDetails.suffix}<|fim_middle|>"
            return if (infillDetails.context == null || infillDetails.context.contextElements.isEmpty()) {
                infillPrompt
            } else {
                infillDetails.context.contextElements.map {
                    "<|file_separator|>${it.filePath()} \n" +
                            it.text()
                }.joinToString("") { it + "\n" } +
                        "<|file_separator|>${infillDetails.context.enclosingElement.filePath()} \n" +
                        infillPrompt
            }
        }
    },
    CODE_QWEN("CodeQwen1.5", listOf("<|endoftext|>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            // see https://github.com/QwenLM/CodeQwen1.5?tab=readme-ov-file#2-file-level-code-completion-fill-in-the-middle
            val infillPrompt =
                "<fim_prefix>${infillDetails.prefix}<fim_suffix>${infillDetails.suffix}<fim_middle>"
            return if (infillDetails.context == null || infillDetails.context.contextElements.isEmpty()) {
                infillPrompt
            } else {
                "<reponame>${infillDetails.context.getRepoName()}\n" +
                        infillDetails.context.contextElements.map {
                            "<file_sep>${it.filePath()} \n" +
                                    it.text()
                        }.joinToString("") { it + "\n" } +
                        "<file_sep>${infillDetails.context.enclosingElement.filePath()} \n" +
                        infillPrompt
            }
        }
    },
    CODE_QWEN_2_5(
        "CodeQwen2.5",
        listOf(
            "diff --git",
            "package ",
            "import ",
            "<|endoftext|>",
            "<|fim_prefix|>",
            "<|fim_middle|>",
            "<|fim_suffix|>",
            "<|fim_pad|>",
            "<|cursor|>",
            "<|repo_name|>",
            "<|file_sep|>",
            "<|im_start|>",
            "<|im_end|>"
        )
    ) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            val infillPrompt =
                "<|fim_prefix|> ${infillDetails.prefix} <|fim_suffix|>${infillDetails.suffix} <|fim_middle|>"

            return when {
                infillDetails.dependenciesStructure != null -> {
                    "<|repo_name|>${infillDetails.repositoryName}\n" +
                            infillDetails.dependenciesStructure.joinToString(separator = "\n", prefix = "\n") {
                                "<|file_sep|>${it.name.value}\n${ClassStructureSerializer.serialize(it)}\n"
                            } +
                            infillDetails.context?.contextElements?.ifNotEmpty {
                                map {
                                    "<|file_sep|>${it.filePath()} \n" +
                                            it.text()
                                }.joinToString("") { it + "\n" } +
                                        "<|file_sep|>${infillDetails.context.enclosingElement.filePath()} \n"
                            } +
                            infillPrompt

                }

                infillDetails.context != null && infillDetails.context.contextElements.isNotEmpty() -> {
                    "<|repo_name|>${infillDetails.context.getRepoName()}\n" +
                            infillDetails.context.contextElements.map {
                                "<|file_sep|>${it.filePath()} \n" +
                                        it.text()
                            }.joinToString("") { it + "\n" } +
                            "<|file_sep|>${infillDetails.context.enclosingElement.filePath()} \n" +
                            infillPrompt
                }

                else -> {
                    infillPrompt
                }
            }
        }
    },
    STABILITY("Stability AI", listOf("<|endoftext|>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            val infillPrompt =
                "<fim_prefix>${infillDetails.prefix}<fim_suffix>${infillDetails.suffix}<fim_middle>"
            return createDefaultMultiFilePrompt(infillDetails, infillPrompt)
        }
    },
    DEEPSEEK_CODER("DeepSeek Coder", listOf("<|EOT|>", "<｜end▁of▁sentence｜>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            // see https://github.com/deepseek-ai/DeepSeek-Coder?tab=readme-ov-file#2-code-insertion
            val infillPrompt =
                "<｜fim▁begin｜>${infillDetails.prefix}<｜fim▁hole｜>${infillDetails.suffix}<｜fim▁end｜>"
            return if (infillDetails.context == null || infillDetails.context.contextElements.isEmpty()) {
                infillPrompt
            } else {
                infillDetails.context.contextElements.map { "#${it.filePath()}\n" + it.text() }
                    .joinToString("") { it + "\n" } +
                        "#${infillDetails.context.enclosingElement.filePath()}\n" +
                        infillPrompt
            }
        }
    },
    STAR_CODER("StarCoder2", listOf("<|endoftext|>", "<file_sep>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            // see https://huggingface.co/spaces/bigcode/bigcode-playground/blob/main/app.py
            val infillPrompt =
                "<fim_prefix>${infillDetails.prefix} <fim_suffix> ${infillDetails.suffix}<fim_middle>"
            return if (infillDetails.context == null || infillDetails.context.contextElements.isEmpty()) {
                infillPrompt
            } else {
                "<reponame>${infillDetails.context.getRepoName()}" +
                        infillDetails.context.contextElements.map {
                            "<filename>${it.filePath()}\n" +
                                    it.text() + "<|endoftext|>"
                        }.joinToString("") { it + "\n" } +
                        "<filename>${infillDetails.context.enclosingElement.filePath()} \n" +
                        infillPrompt
            }
        }
    },
    CODESTRAL("Codestral", listOf("</s>")) {
        override fun buildPrompt(infillDetails: InfillRequest): String {
            // see https://github.com/mistralai/mistral-common/blob/master/src/mistral_common/tokens/tokenizers/base.py
            val infillPrompt =
                "[SUFFIX]${infillDetails.suffix}[PREFIX]${infillDetails.prefix}[MIDDLE]"
            return createDefaultMultiFilePrompt(infillDetails, infillPrompt)
        }
    };

    abstract fun buildPrompt(infillDetails: InfillRequest): String

    override fun toString(): String {
        return label
    }

    companion object {
        private fun createDefaultMultiFilePrompt(
            infillDetails: InfillRequest,
            infillPrompt: String
        ): String {
            val context = infillDetails.context
            return if (context == null || context.contextElements.isEmpty()) {
                infillPrompt
            } else {
                context.contextElements.map {
                    "# ${it.filePath()} \n" +
                            it.text()
                }.joinToString("") { it + "\n" } +
                        "# ${context.enclosingElement.filePath()} \n" +
                        infillPrompt
            }
        }
    }
}
