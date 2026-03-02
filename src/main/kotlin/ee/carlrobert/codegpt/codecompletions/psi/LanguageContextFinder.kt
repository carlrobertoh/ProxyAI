package ee.carlrobert.codegpt.codecompletions.psi

import com.intellij.psi.PsiElement
import ee.carlrobert.codegpt.codecompletions.InfillContext

interface LanguageContextFinder {
    /**
     * Determines relevant enclosing [PsiElement] and [PsiElement]s relevant to the context and returns their source code [PsiElement].
     */
    fun findContext(psiElement: PsiElement): InfillContext
}
