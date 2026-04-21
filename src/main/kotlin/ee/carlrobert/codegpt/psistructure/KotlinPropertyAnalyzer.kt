@file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package ee.carlrobert.codegpt.psistructure

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance

@OptIn(KaExperimentalApi::class)
class KotlinPropertyAnalyzer {

    fun resolveInferredType(property: KtProperty): String {
        return try {
            analyze(property) {
                property.returnType.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
            }
        } catch (_: Exception) {
            TYPE_UNKNOWN
        }
    }
}

private const val TYPE_UNKNOWN = "TypeUnknown"
