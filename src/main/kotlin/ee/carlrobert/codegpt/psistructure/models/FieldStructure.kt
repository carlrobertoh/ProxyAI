package ee.carlrobert.codegpt.psistructure.models

data class FieldStructure(
    val name: String,
    val type: ClassName,
    val modifiers: List<String>,
)
