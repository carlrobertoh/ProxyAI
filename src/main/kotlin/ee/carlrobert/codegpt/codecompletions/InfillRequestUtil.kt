package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.refactoring.suggested.startOffset
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.psi.CompletionContextService
import ee.carlrobert.codegpt.codecompletions.psi.readText
import ee.carlrobert.codegpt.psistructure.PsiStructureProvider
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.util.GitUtil


object InfillRequestUtil {

    suspend fun buildInfillRequest(request: InlineCompletionRequest): InfillRequest {
        val caretOffset = readAction { request.editor.caretModel.offset }
        val infillRequestBuilder = InfillRequest.Builder(request.document, caretOffset)
            .fileDetails(
                InfillRequest.FileDetails(
                    request.document.text,
                    request.file.virtualFile.path
                )
            )

        val project = request.editor.project ?: return infillRequestBuilder.build()
        if (service<ConfigurationSettings>().state.codeCompletionSettings.gitDiffEnabled) {
            val additionalContext = GitUtil.getCurrentChanges(project)
            if (!additionalContext.isNullOrEmpty()) {
                infillRequestBuilder.additionalContext(additionalContext)
            }
        }

        if (service<ConfigurationSettings>().state.codeCompletionSettings.contextAwareEnabled) {
            getInfillContext(request, caretOffset)?.let {
                infillRequestBuilder.context(it)
                infillRequestBuilder.addRepositoryName(it.getRepoName())
            }
        }

        if (service<ConfigurationSettings>().state.codeCompletionSettings.collectDependencyStructure) {
            val depth = service<ConfigurationSettings>().state.codeCompletionSettings.psiStructureAnalyzeDepth
            val psiStructure = PsiStructureProvider().get(listOf(request.file), depth)
            if (psiStructure.isNotEmpty()) {
                infillRequestBuilder.addDependenciesStructure(psiStructure)
                infillRequestBuilder.addRepositoryName(psiStructure.first().repositoryName)
            }
        }

        return infillRequestBuilder.build()
    }

    private fun getInfillContext(
        request: InlineCompletionRequest,
        caretOffset: Int
    ): InfillContext? {
        val infillContext =
            service<CompletionContextService>().findContext(request.editor, caretOffset)
                ?: return null
        val caretInEnclosingElement =
            caretOffset - infillContext.enclosingElement.psiElement.startOffset
        val entireText = infillContext.enclosingElement.psiElement.readText()
        val prefix = entireText.take(caretInEnclosingElement)
        val suffix =
            if (entireText.length < caretInEnclosingElement) "" else entireText.takeLast(
                entireText.length - caretInEnclosingElement
            )
        return truncateContext(prefix + suffix, infillContext)
    }

    private fun truncateContext(prompt: String, infillContext: InfillContext): InfillContext {
        var promptTokens = EncodingManager.getInstance().countTokens(prompt)
        val truncatedContextElements = infillContext.contextElements.takeWhile {
            promptTokens += it.tokens
            promptTokens <= MAX_PROMPT_TOKENS
        }.toSet()
        return InfillContext(infillContext.enclosingElement, truncatedContextElements)
    }
}