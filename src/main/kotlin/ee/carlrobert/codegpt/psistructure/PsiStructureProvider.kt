package ee.carlrobert.codegpt.psistructure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import org.jetbrains.kotlin.psi.KtFile

class PsiStructureProvider {

    private val kotlinFileAnalyzerAvailable: Boolean =
        ApplicationManager.getApplication().hasComponent(KotlinFileAnalyzer::class.java)

    fun get(psiFiles: List<PsiFile>): Set<ClassStructure> =
        ReadAction.compute<Set<ClassStructure>, Throwable> {
            val classStructureSet = mutableSetOf<ClassStructure>()
            val processedPsiFiles = mutableSetOf<PsiFile?>()
            val psiFileQueue = PsiFileQueue(psiFiles)

            while (true) {
                val psiFile = psiFileQueue.pop()
                when {
                    processedPsiFiles.contains(psiFile) -> Unit

                    kotlinFileAnalyzerAvailable && psiFile is KtFile -> {
                        classStructureSet.addAll(KotlinFileAnalyzer(psiFileQueue, psiFile).analyze())
                        processedPsiFiles.add(psiFile)
                    }

                    psiFile == null -> break
                }
            }

            return@compute classStructureSet.toSet()
        }
}