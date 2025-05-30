package ee.carlrobert.codegpt.psistructure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.util.io.await
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import ee.carlrobert.codegpt.util.coroutines.runCatchingCancellable
import kotlinx.coroutines.*
import org.jetbrains.kotlin.psi.KtFile

class PsiStructureProvider {

    suspend fun get(psiFiles: List<PsiFile>): Set<ClassStructure> {
        var result: Set<ClassStructure>? = null
        var attempts = 0
        val maxAttempts = 5

        val kotlinFileAnalyzerAvailable: Boolean = ApplicationManager
            .getApplication()
            .hasComponent(KotlinFileAnalyzer::class.java)

        while (result == null && attempts < maxAttempts) {
            attempts++
            runCatchingCancellable {
                val project = psiFiles
                    .map { it.project }
                    .firstOrNull { !it.isDisposed } ?: error("Project not available")

                val coroutineContext = currentCoroutineContext()
                val future = ReadAction.nonBlocking<Set<ClassStructure>> {
                    val classStructureSet = mutableSetOf<ClassStructure>()
                    val processedPsiFiles = mutableSetOf<PsiFile?>()
                    val psiFileQueue = PsiFileQueue(psiFiles)

                    while (true) {
                        coroutineContext.ensureActive()

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

                    classStructureSet.toSet()
                }
                    .inSmartMode(project)
                    .coalesceBy(this@PsiStructureProvider)
                    .submit(Dispatchers.Default.asExecutor())

                result = future.await()
            }.onFailure {
                delay(DELAY_RESTART_READ_ACTION)
            }
        }

        return result ?: emptySet()
    }

    private companion object {
        const val DELAY_RESTART_READ_ACTION = 200L
    }
}