package ee.carlrobert.codegpt.psistructure

import com.intellij.psi.PsiFile

class PsiFileDepthQueue(
    initial: List<PsiFile>,
    private val maxDepth: Int = -1,
) {

    private val psiFiles = mutableSetOf<PsiDepthFile>().apply {
        addAll(initial.map { PsiDepthFile(it, 0) })
    }

    private val queue = ArrayDeque(initial.map { PsiDepthFile(it, 0) })

    @Synchronized
    fun pop(): PsiFile? {
        while (queue.isNotEmpty()) {
            val first = queue.first()
            if (maxDepth == -1 || first.depth <= maxDepth) {
                return queue.removeFirst().psiFile
            } else {
                queue.removeFirst()
            }
        }
        return null
    }

    @Synchronized
    fun put(psiFile: PsiFile, baseFileName: String) {
        if (psiFiles.any { it.psiFile.name == psiFile.name }) return
        val baseFileDepth = psiFiles.find { it.psiFile.name == baseFileName }?.depth ?: 0
        val newItem = PsiDepthFile(psiFile, baseFileDepth + 1)
        queue.add(newItem)
        psiFiles.add(newItem)
    }
}