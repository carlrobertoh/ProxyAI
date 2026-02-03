package ee.carlrobert.codegpt.settings.skills

object SkillPromptFormatter {

    fun formatForSystemPrompt(skills: List<SkillDescriptor>): String {
        if (skills.isEmpty()) return ""
        val items = skills.joinToString("\n") { skill ->
            val normalizedDescription = normalizeDescription(skill.description)
            "- ${skill.name}: $normalizedDescription"
        }
        return """
            The following skills are available for use with the Skill tool:
            
            $items
        """.trimIndent()
    }

    private fun normalizeDescription(description: String): String {
        val lines = description
            .trim()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines.first()
        return buildString {
            append(lines.first())
            lines.drop(1).forEach { line ->
                append("\n  ")
                append(line)
            }
        }
    }
}
