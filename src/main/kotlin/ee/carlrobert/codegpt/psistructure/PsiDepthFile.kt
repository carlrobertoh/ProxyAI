package ee.carlrobert.codegpt.psistructure

import com.intellij.psi.PsiFile

data class PsiDepthFile(
    val psiFile: PsiFile,
    val depth: Int,
)