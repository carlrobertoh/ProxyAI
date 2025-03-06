package ee.carlrobert.codegpt.psistructure.models

data class ParameterInfo(
    val name: String,
    val type: ClassName,
    val modifiers: List<String>,
)
