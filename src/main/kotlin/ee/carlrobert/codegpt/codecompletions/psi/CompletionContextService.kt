package ee.carlrobert.codegpt.codecompletions.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.codecompletions.InfillContext

@Service(Service.Level.PROJECT)
class CompletionContextService {

    companion object {
        private val CONTEXT_FINDERS = mapOf(
            "JAVA" to JavaContextFinder::class.java,
            // "Python" to PythonContextFinder::class.java
        )
    }

    /**
     * Determines the [PsiElement] at the given offset,
     * determines relevant context with the help of [LanguageContextFinder]s
     * and returns the context with the relevant enclosing [PsiElement] and a set of source code [PsiElement]s.
     */
    fun findContext(editor: Editor, offset: Int): InfillContext? {
        return ReadAction.compute<InfillContext, Throwable> {
            val psiFile = PsiManager.getInstance(editor.project!!).findFile(editor.virtualFile!!)!!
            val psiElement = psiFile.findElementAt(offset) ?: return@compute null
            val contextFinderClass = CONTEXT_FINDERS[psiElement.language.id]
                ?: // No context finder for the language implemented yet
                return@compute null
            val contextFinder = ApplicationManager.getApplication().getService(contextFinderClass)
                ?: // A context finder for the language exists but not available in the used IDE
                return@compute null
            val context = contextFinder.findContext(psiElement)
            val encodingManager = EncodingManager.getInstance()
            context.enclosingElement.tokens =
                encodingManager.countTokens(context.enclosingElement.psiElement.text)
            context.contextElements.forEach {
                it.tokens = encodingManager.countTokens(it.psiElement.text)
            }
            return@compute context
        }

    }
}
