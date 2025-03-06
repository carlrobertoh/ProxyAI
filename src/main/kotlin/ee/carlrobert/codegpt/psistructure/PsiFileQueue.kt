package ee.carlrobert.codegpt.psistructure

import com.intellij.psi.PsiFile

class PsiFileQueue(
    initial: List<PsiFile>
) {

    private val queue = ArrayDeque(initial)

    @Synchronized
    fun pop(): PsiFile? =
        queue.removeFirstOrNull()

    @Synchronized
    fun put(psiFile: PsiFile) {
        queue.add(psiFile)
    }
}