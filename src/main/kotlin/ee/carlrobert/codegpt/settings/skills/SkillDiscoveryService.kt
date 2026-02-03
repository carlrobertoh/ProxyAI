package ee.carlrobert.codegpt.settings.skills

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.io.path.relativeToOrNull

@Service(Service.Level.PROJECT)
class SkillDiscoveryService(private val project: Project) {

    companion object {
        private const val SKILLS_DIRECTORY = ".proxyai/skills"
        private const val SKILL_FILE_NAME = "SKILL.md"
    }

    private val logger = thisLogger()

    fun listSkills(): List<SkillDescriptor> {
        val rootPath = project.basePath?.let { Path.of(it) } ?: return emptyList()
        val skillsRoot = rootPath.resolve(SKILLS_DIRECTORY)
        if (!Files.exists(skillsRoot) || !Files.isDirectory(skillsRoot)) return emptyList()

        val skillFiles = Files.walk(skillsRoot).use { stream ->
            stream
                .filter { path ->
                    Files.isRegularFile(path) &&
                            path.fileName.toString().equals(SKILL_FILE_NAME, ignoreCase = true)
                }
                .toList()
        }

        return skillFiles.mapNotNull { file ->
            parseSkillFile(file, rootPath)
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun parseSkillFile(skillFile: Path, projectRoot: Path): SkillDescriptor? {
        return try {
            val raw = Files.readString(skillFile)
            val (frontmatter, body) = extractFrontmatter(raw)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: run {
                logger.warn("Skipping skill without required 'name' in: $skillFile")
                return null
            }
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: run {
                logger.warn("Skipping skill without required 'description' in: $skillFile")
                return null
            }
            val title = frontmatter["title"]?.takeIf { it.isNotBlank() }
                ?: extractTitleFromBody(body)
                ?: name
            val relativePath = skillFile.relativeToOrNull(projectRoot)?.toString() ?: skillFile.name
            SkillDescriptor(
                name = name,
                title = title,
                description = description,
                content = body.trim(),
                relativePath = relativePath
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse skill file: $skillFile", e)
            null
        }
    }

    private fun extractFrontmatter(markdown: String): Pair<Map<String, String>, String> {
        val lines = markdown.lines()
        if (lines.firstOrNull()?.trim() != "---") {
            return emptyMap<String, String>() to markdown
        }

        val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIndex == -1) {
            return emptyMap<String, String>() to markdown
        }

        val frontmatterLines = lines.subList(1, endIndex + 1)
        val metadata = parseFrontmatterLines(frontmatterLines)
        val body = lines.drop(endIndex + 2).joinToString("\n")

        return metadata to body
    }

    private fun parseFrontmatterLines(lines: List<String>): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val parsed = parseKeyValue(line)

            if (parsed == null) {
                index++
                continue
            }

            val (key, rawValue) = parsed

            if (rawValue == "|" || rawValue == ">") {
                val (value, consumed) = parseMultilineValue(lines, index + 1, folded = rawValue == ">")
                if (value.isNotBlank()) {
                    metadata[key] = value
                }
                index += consumed + 1
            } else {
                val value = rawValue.trim('"', '\'')
                if (value.isNotBlank()) {
                    metadata[key] = value
                }
                index++
            }
        }

        return metadata
    }

    private fun parseKeyValue(line: String): Pair<String, String>? {
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) return null

        val key = line.substring(0, colonIndex).trim().lowercase(Locale.getDefault())
        if (key.isBlank()) return null

        val value = line.substring(colonIndex + 1).trim()
        return key to value
    }

    private fun parseMultilineValue(lines: List<String>, startIndex: Int, folded: Boolean): Pair<String, Int> {
        val blockLines = lines
            .drop(startIndex)
            .takeWhile { it.startsWith(" ") || it.startsWith("\t") }
            .map { it.trimStart() }

        val separator = if (folded) " " else "\n"
        val value = blockLines.joinToString(separator).trim()

        return value to blockLines.size
    }

    private fun extractTitleFromBody(body: String): String? {
        return body.lines()
            .firstOrNull { it.trim().startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
