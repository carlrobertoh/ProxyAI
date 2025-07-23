package ee.carlrobert.codegpt.util

object ProjectPathUtils {
    private const val PROJECT_NAME_MAX_LENGTH = 30

    fun extractProjectName(projectPath: String?): String? {
        if (projectPath.isNullOrEmpty()) return null
        
        val separators = listOf('/', '\\')
        val lastSeparatorIndex = separators.maxOfOrNull { projectPath.lastIndexOf(it) } ?: -1
        
        return if (lastSeparatorIndex >= 0 && lastSeparatorIndex < projectPath.length - 1) {
            projectPath.substring(lastSeparatorIndex + 1)
        } else {
            projectPath
        }
    }

    fun extractProjectNameTruncated(projectPath: String?): String? {
        val projectName = extractProjectName(projectPath) ?: return null
        
        return if (projectName.length > PROJECT_NAME_MAX_LENGTH) {
            projectName.substring(0, PROJECT_NAME_MAX_LENGTH - 3) + "..."
        } else {
            projectName
        }
    }
}