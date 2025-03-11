package ee.carlrobert.codegpt.psistructure.models

data class ConstructorStructure(
    val parameters: List<ParameterInfo>,
    val modifiers: List<String>,
)
