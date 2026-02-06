package ee.carlrobert.codegpt.settings

import ai.koog.agents.core.tools.Tool
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.settings.agents.SubagentDefaults
import ee.carlrobert.codegpt.settings.hooks.HookConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

@Service(Service.Level.PROJECT)
class ProxyAISettingsService(private val project: Project) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val logger = thisLogger()
    private val settingsFile: Path by lazy {
        Paths.get(project.basePath ?: "", ".proxyai", "settings.json")
    }
    private val store: ProxyAISettingsStore by lazy {
        ProxyAISettingsStore(settingsFile, json, logger)
    }
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    fun getSubagents(): List<ProxyAISubagent> {
        val settings = snapshot().settings
        return SubagentDefaults.ensureBuiltIns(settings.subagents)
    }

    fun getSettings(): ProxyAISettings {
        return snapshot().settings
    }

    fun getHooks(): HookConfiguration {
        return snapshot().settings.hooks ?: HookConfiguration()
    }

    fun saveSubagents(subagents: List<ProxyAISubagent>) {
        val normalized = SubagentDefaults.ensureBuiltIns(subagents)
        updateSettings { it.copy(subagents = normalized) }
    }

    fun saveHooks(configuration: HookConfiguration?) {
        updateSettings { it.copy(hooks = configuration) }
    }

    fun evaluateToolPermission(tool: Tool<*, *>, target: String): ToolPermissionPolicy.Decision {
        val settings = snapshot().settings
        val targets = permissionTargets(target)
        return ToolPermissionPolicy.evaluate(
            permissions = ToolPermissionPolicy.PermissionLists(
                allow = settings.permissions.allow,
                ask = settings.permissions.ask,
                deny = settings.permissions.deny
            ),
            toolName = tool.name,
            targets = targets
        )
    }

    fun isToolInvocationDenied(tool: Tool<*, *>, target: String): Boolean {
        return evaluateToolPermission(tool, target) == ToolPermissionPolicy.Decision.DENY
    }

    fun isToolInvocationWhitelisted(tool: Tool<*, *>, target: String): Boolean {
        return evaluateToolPermission(tool, target) == ToolPermissionPolicy.Decision.ALLOW
    }

    fun hasAllowRulesForTool(toolName: String): Boolean {
        val allows = snapshot().settings.permissions.allow
        return allows.any { rule ->
            val trimmed = rule.trim()
            trimmed == toolName || trimmed.startsWith("$toolName(")
        }
    }

    fun isPathIgnored(path: String): Boolean {
        return isPathIgnored(path, project.basePath ?: "")
    }

    fun isPathIgnored(path: String, basePath: String): Boolean {
        return snapshot().ignoreMatcher.matches(path, basePath)
    }

    fun isPathVisible(path: String): Boolean {
        return !isPathIgnored(path)
    }

    fun isVirtualFileVisible(file: VirtualFile): Boolean {
        return isPathVisible(file.path)
    }

    private fun permissionTargets(target: String): List<String> {
        val normalized = try {
            Paths.get(target).normalize().toString().replace('\\', '/')
        } catch (_: Exception) {
            target.replace('\\', '/')
        }
        val fileName = normalized.substringAfterLast('/')
        val base = (project.basePath ?: "").replace('\\', '/').trimEnd('/')
        if (base.isBlank()) return listOf(normalized, target)

        val rel = if (normalized.startsWith(base)) {
            normalized.removePrefix(base).removePrefix("/")
        } else null

        return buildList {
            add(target)
            add(normalized)
            if (fileName.isNotBlank()) add(fileName)
            if (!rel.isNullOrBlank()) {
                add(rel)
                add("./$rel")
            }
        }.distinct()
    }

    private fun snapshot(): SettingsSnapshot {
        val settings = store.load() ?: ProxyAISettings.default()
        val ignoreMatcher = IgnoreMatcher.from(settings.ignore, isWindows)
        return SettingsSnapshot(settings, ignoreMatcher)
    }

    private fun updateSettings(transform: (ProxyAISettings) -> ProxyAISettings) {
        val current = snapshot().settings
        val updated = transform(current)
        store.save(updated)
    }
}

private data class SettingsSnapshot(
    val settings: ProxyAISettings,
    val ignoreMatcher: IgnoreMatcher
)

private class ProxyAISettingsStore(
    private val settingsFile: Path,
    private val json: Json,
    private val logger: Logger
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun load(): ProxyAISettings? {
        if (settingsFile.notExists()) return null
        return try {
            settingsFile.inputStream().use { inputStream ->
                json.decodeFromStream<ProxyAISettings>(inputStream)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load ProxyAI settings from $settingsFile", e)
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(settings: ProxyAISettings): Boolean {
        return try {
            settingsFile.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(
                settingsFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { outputStream ->
                json.encodeToStream(settings, outputStream)
            }
            true
        } catch (e: Exception) {
            logger.warn("Failed to write ProxyAI settings to $settingsFile", e)
            false
        }
    }

}

private class IgnoreMatcher(
    private val compiled: List<Pair<String, Regex>>,
    private val isWindows: Boolean
) {
    fun matches(path: String, basePath: String): Boolean {
        val normalizedPath = normalizePath(path)
        val rel = if (basePath.isNotBlank() && normalizedPath.startsWith(basePath)) {
            normalizedPath.removePrefix(basePath).removePrefix("/")
        } else normalizedPath

        val pathToTest = if (isWindows) normalizedPath.lowercase() else normalizedPath
        val relToTest = if (isWindows) rel.lowercase() else rel
        return compiled.any { (raw, rx) ->
            val normalized = if (isWindows) raw.lowercase() else raw
            simpleMatch(relToTest, normalized) ||
                    simpleMatch(pathToTest, normalized) ||
                    rx.matches(relToTest) ||
                    rx.matches(pathToTest)
        }
    }

    companion object {
        fun from(patterns: List<String>, isWindows: Boolean): IgnoreMatcher {
            val compiled = patterns.mapNotNull { pattern ->
                val rx = globToRegex(pattern, ignoreCase = isWindows) ?: return@mapNotNull null
                pattern to rx
            }
            return IgnoreMatcher(compiled, isWindows)
        }

        private fun globToRegex(glob: String, ignoreCase: Boolean = false): Regex? {
            return try {
                var g = glob.trim()
                if (g.isEmpty()) return null
                val deepDirSuffix = g.endsWith("/**")
                val dirSuffix = g.endsWith("/")
                if (deepDirSuffix) {
                    g = g.removeSuffix("/**")
                }
                g = g.trimEnd('/')
                val sb = StringBuilder()
                sb.append('^')
                if (!g.startsWith("/")) {
                    // Match from project root or any nested directory, similar to gitignore-style rules.
                    sb.append("(?:.*/)?")
                }
                var i = 0
                while (i < g.length) {
                    when (val c = g[i]) {
                        '*' -> {
                            val nextStar = (i + 1 < g.length && g[i + 1] == '*')
                            if (nextStar) {
                                sb.append(".*")
                                i += 1
                            } else {
                                sb.append("[^/]*")
                            }
                        }

                        '?' -> sb.append(".")
                        '.', '(', ')', '+', '|', '^', '$', '@', '%' -> sb.append('\\').append(c)
                        '[' -> sb.append("[")
                        ']' -> sb.append("]")
                        '{' -> sb.append('(')
                        '}' -> sb.append(')')
                        ',' -> sb.append('|')
                        else -> sb.append(c)
                    }
                    i += 1
                }
                when {
                    deepDirSuffix -> sb.append("(/.*)?")
                    dirSuffix -> sb.append("(/.*)?")
                }
                sb.append('$')
                if (ignoreCase) Regex(
                    sb.toString(),
                    RegexOption.IGNORE_CASE
                ) else Regex(sb.toString())
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun normalizePath(path: String): String {
        return try {
            Paths.get(path).normalize().toString().replace('\\', '/')
        } catch (_: Exception) {
            path.replace('\\', '/')
        }
    }

    private fun simpleMatch(path: String, pattern: String): Boolean {
        val p = path.trimStart('/').replace('\\', '/')
        val pat = pattern.trim().replace('\\', '/')
        if (pat == p) return true
        if (pat.endsWith("/**")) {
            val dir = pat.removeSuffix("/**").trimEnd('/')
            if (p == dir || p.startsWith("$dir/") || p.contains("/$dir/")) return true
        }
        if (pat.endsWith("/")) {
            val dir = pat.trimEnd('/')
            if (p == dir || p.startsWith("$dir/") || p.contains("/$dir/")) return true
        }
        if (!pat.contains('/') && pat.startsWith('.')) {
            val fileName = p.substringAfterLast('/')
            if (fileName == pat) return true
        }
        return false
    }
}

@Serializable
data class ProxyAISettings(
    val ignore: List<String> = DEFAULT_IGNORE_PATTERNS,
    val permissions: Permissions = Permissions(allow = DEFAULT_ALLOW_RULES),
    val subagents: List<ProxyAISubagent> = emptyList(),
    val hooks: HookConfiguration? = null
) {
    companion object {
        private val DEFAULT_IGNORE_PATTERNS = listOf(
            ".idea/",
            "*.iml",
            ".git/",
        )

        private val DEFAULT_ALLOW_RULES = listOf(
            "Bash(rg *)",
            "Bash(grep *)",
            "Bash(find *)",
            "Bash(ls *)",
            "Bash(sed *)",
            "Bash(rm *)",
            "Bash(cat *)",
            "Bash(head *)",
            "Bash(tail *)",
            "Bash(diff *)",
            "Bash(du *)",
            "Bash(file *)",
            "Bash(sort *)",
            "Bash(stat *)",
            "Bash(tree *)",
            "Bash(uniq *)",
            "Bash(wc *)",
            "Bash(whereis *)",
            "Bash(which *)",
            "Bash(less *)",
            "Bash(more *)",
        )

        fun default(): ProxyAISettings {
            return ProxyAISettings(
                ignore = DEFAULT_IGNORE_PATTERNS,
                permissions = Permissions(
                    allow = DEFAULT_ALLOW_RULES
                ),
                subagents = SubagentDefaults.defaults(),
                hooks = HookConfiguration()
            )
        }
    }

    @Serializable
    data class Permissions(
        val allow: List<String> = emptyList(),
        val ask: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )
}

@Serializable
data class ProxyAISubagent(
    val id: Int,
    val title: String,
    val objective: String,
    val tools: List<String>
)
