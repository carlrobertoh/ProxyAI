package ee.carlrobert.codegpt.psistructure.models

data class MethodStructure(
    val name: String,
    val returnType: ClassName,
    val parameters: List<ParameterInfo>,
    val modifiers: List<String>,
)
