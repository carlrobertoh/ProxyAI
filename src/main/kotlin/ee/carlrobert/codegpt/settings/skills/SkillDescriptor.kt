package ee.carlrobert.codegpt.settings.skills

data class SkillDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val content: String,
    val relativePath: String
)
