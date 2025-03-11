package ee.carlrobert.codegpt.codecompletions

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.psi.filePath
import ee.carlrobert.codegpt.codecompletions.psi.readText
import ee.carlrobert.codegpt.psistructure.models.ClassStructure

const val MAX_PROMPT_TOKENS = 256

class InfillRequest private constructor(
    val prefix: String,
    val suffix: String,
    val caretOffset: Int,
    val fileDetails: FileDetails?,
    val repositoryName: String?,
    val dependenciesStructure: Set<ClassStructure>?,
    val context: InfillContext?,
    val stopTokens: List<String>,
) {

    data class FileDetails(val fileContent: String, val fileExtension: String? = null)

    class Builder {
        private val prefix: String
        private val suffix: String
        private val caretOffset: Int
        private var fileDetails: FileDetails? = null
        private var additionalContext: String? = null
        private var repositoryName: String? = null
        private var dependenciesStructure: Set<ClassStructure>? = null
        private var context: InfillContext? = null
        private var stopTokens: List<String>

        constructor(
            prefix: String,
            suffix: String,
            caretOffset: Int,
            type: CompletionType = CompletionType.MULTI_LINE
        ) {
            this.prefix = prefix
            this.suffix = suffix
            this.caretOffset = caretOffset
            this.stopTokens = getStopTokens(type)
        }

        constructor(
            document: Document,
            caretOffset: Int,
            type: CompletionType = CompletionType.MULTI_LINE
        ) {
            prefix =
                document.getText(TextRange(0, caretOffset))
                    .truncateText(MAX_PROMPT_TOKENS, false)
            suffix =
                document.getText(TextRange(caretOffset, document.textLength))
                    .truncateText(MAX_PROMPT_TOKENS)
            this.caretOffset = caretOffset
            this.stopTokens = getStopTokens(type)
        }

        fun fileDetails(fileDetails: FileDetails) = apply { this.fileDetails = fileDetails }
        fun additionalContext(additionalContext: String) =
            apply { this.additionalContext = additionalContext }

        fun addRepositoryName(repositoryName: String) =
            apply { this.repositoryName = repositoryName }

        fun addDependenciesStructure(dependenciesStructure: Set<ClassStructure>) =
            apply { this.dependenciesStructure = dependenciesStructure }

        fun context(context: InfillContext) = apply { this.context = context }

        private fun getStopTokens(type: CompletionType): List<String> {
            var whitespaceCount = 0
            val lineSuffix = suffix
                .takeWhile { char ->
                    if (char == '\n') false
                    else if (char.isWhitespace()) whitespaceCount++ < 2
                    else whitespaceCount < 2
                }
            val baseTokens = when (type) {
                CompletionType.SINGLE_LINE -> emptyList()
                else -> listOf("\n\n")
            }

            return if (lineSuffix.isNotEmpty()) {
                baseTokens + lineSuffix
            } else {
                baseTokens
            }
        }

        fun build(): InfillRequest {
            val modifiedPrefix = if (!additionalContext.isNullOrEmpty()) {
                "/*\n${additionalContext}\n*/\n\n$prefix"
            } else {
                prefix
            }
            return InfillRequest(
                modifiedPrefix,
                suffix,
                caretOffset,
                fileDetails,
                repositoryName,
                dependenciesStructure,
                context,
                stopTokens,
            )
        }
    }
}

class InfillContext(
    val enclosingElement: ContextElement,
    // TODO: Add some kind of ranking, which contextElements are more important than others
    val contextElements: Set<ContextElement>
) {

    fun getRepoName(): String = enclosingElement.psiElement.project.name
}

class ContextElement(val psiElement: PsiElement) {
    var tokens: Int = -1

    fun filePath() = this.psiElement.filePath()
    fun text() = this.psiElement.readText()
}

fun String.truncateText(maxTokens: Int, fromStart: Boolean = true): String {
    return service<EncodingManager>().truncateText(this, maxTokens, fromStart)
}

enum class CompletionType {
    SINGLE_LINE,
    MULTI_LINE,
}