@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package ee.carlrobert.codegpt.psistructure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.util.io.await
import ee.carlrobert.codegpt.psistructure.models.ClassStructure
import kotlinx.coroutines.*
import org.jetbrains.kotlin.psi.KtFile
import kotlin.coroutines.cancellation.CancellationException

class PsiStructureProvider {

    suspend fun get(
        psiFiles: List<PsiFile>,
        analyzeDepth: Int,
    ): Set<ClassStructure> {
        val physicalPsiFiles = psiFiles.filter { psiFile ->
            psiFile.virtualFile?.isValid == true
        }
        if (physicalPsiFiles.isEmpty()) {
            return emptySet()
        }

        var result: Set<ClassStructure>? = null
        var attempts = 0
        val maxAttempts = 5

        val kotlinFileAnalyzerAvailable: Boolean = ApplicationManager
            .getApplication()
            .hasComponent(KotlinFileAnalyzer::class.java)

        while (result == null && attempts < maxAttempts) {
            attempts++
            try {
                val project = physicalPsiFiles
                    .map { it.project }
                    .firstOrNull { !it.isDisposed } ?: error("Project not available")

                val coroutineContext = currentCoroutineContext()
                val future = ReadAction.nonBlocking<Set<ClassStructure>> {
                    val classStructureSet = mutableSetOf<ClassStructure>()
                    val processedPsiFiles = mutableSetOf<PsiFile?>()
                    val psiFileDepthQueue = PsiFileDepthQueue(physicalPsiFiles, analyzeDepth)

                    while (true) {
                        coroutineContext.ensureActive()

                        val psiFile = psiFileDepthQueue.pop()

                        when {
                            processedPsiFiles.contains(psiFile) -> Unit

                            kotlinFileAnalyzerAvailable && psiFile is KtFile -> {
                                classStructureSet.addAll(
                                    KotlinFileAnalyzer(
                                        psiFileQueue = psiFileDepthQueue,
                                        ktFile = psiFile,
                                    ).analyze()
                                )
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(DELAY_RESTART_READ_ACTION)
            }
        }

        return result ?: emptySet()
    }

    private companion object {
        const val DELAY_RESTART_READ_ACTION = 200L
    }
}
