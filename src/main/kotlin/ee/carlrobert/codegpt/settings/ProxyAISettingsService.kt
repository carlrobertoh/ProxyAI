package ee.carlrobert.codegpt.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.settings.agents.SubagentDefaults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

@Service(Service.Level.PROJECT)
class ProxyAISettingsService(private val project: Project) {

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = thisLogger()
    private val settingsFile: Path by lazy {
        Paths.get(project.basePath ?: "", ".proxyai", "settings.json")
    }
    private val store: ProxyAISettingsStore by lazy { ProxyAISettingsStore(settingsFile, json, logger) }
    private val cache = AtomicReference<CachedSettings?>(null)
    private val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    fun getSubagents(): List<ProxyAISubagent> {
        val settings = snapshot().settings
        return SubagentDefaults.ensureBuiltIns(settings.subagents)
    }

    fun saveSubagents(subagents: List<ProxyAISubagent>) {
        val normalized = SubagentDefaults.ensureBuiltIns(subagents)
        updateSettings { it.copy(subagents = normalized) }
    }

    fun getBashPermissions(): List<String> {
        return snapshot().settings.permissions.allow
    }

    fun getIgnorePatterns(): List<String> {
        return snapshot().settings.ignore
    }

    fun isPathIgnored(path: String): Boolean {
        return isPathIgnored(path, project.basePath ?: "")
    }

    fun isPathIgnored(path: String, basePath: String): Boolean {
        return snapshot().ignoreMatcher.matches(path, basePath)
    }

    private fun snapshot(): CachedSettings {
        val cached = cache.get()
        val lastModified = store.lastModified()
        if (cached != null && cached.lastModified == lastModified) {
            return cached
        }
        val settings = store.load() ?: ProxyAISettings.default()
        val ignoreMatcher = IgnoreMatcher.from(settings.ignore, isWindows)
        val snapshot = CachedSettings(settings, lastModified, ignoreMatcher)
        cache.set(snapshot)
        return snapshot
    }

    private fun updateSettings(transform: (ProxyAISettings) -> ProxyAISettings) {
        val current = snapshot().settings
        val updated = transform(current)
        if (!store.save(updated)) return
        val lastModified = store.lastModified()
        val ignoreMatcher = IgnoreMatcher.from(updated.ignore, isWindows)
        cache.set(CachedSettings(updated, lastModified, ignoreMatcher))
    }
}

private data class CachedSettings(
    val settings: ProxyAISettings,
    val lastModified: Long,
    val ignoreMatcher: IgnoreMatcher
)

private class ProxyAISettingsStore(
    private val settingsFile: Path,
    private val json: Json,
    private val logger: Logger
) {
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

    fun lastModified(): Long {
        return try {
            if (settingsFile.notExists()) -1 else Files.getLastModifiedTime(settingsFile).toMillis()
        } catch (e: Exception) {
            logger.warn("Failed to read ProxyAI settings modified time from $settingsFile", e)
            -1
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
                val dirSuffix = g.endsWith("/")
                g = g.trimEnd('/')
                val sb = StringBuilder()
                sb.append('^')
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
                if (dirSuffix) sb.append("(/.*)?")
                sb.append('$')
                if (ignoreCase) Regex(sb.toString(), RegexOption.IGNORE_CASE) else Regex(sb.toString())
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
        val p = path.trimStart('/')
        val pat = pattern.trim()
        if (pat == p) return true
        if (pat.endsWith("/") && p.startsWith(pat.trimEnd('/'))) return true
        if (!pat.contains('/') && pat.startsWith('.')) {
            val fileName = p.substringAfterLast('/')
            if (fileName == pat) return true
        }
        return false
    }
}

@Serializable
data class ProxyAISettings(
    val ignore: List<String>,
    val permissions: Permissions,
    val subagents: List<ProxyAISubagent> = emptyList()
) {
    companion object {
        fun default(): ProxyAISettings {
            return ProxyAISettings(
                ignore = listOf(
                    ".idea/",
                    "*.iml",
                    "build/",
                    "dist/",
                    "node_modules/",
                    ".git/",
                ),
                permissions = Permissions(
                    allow = listOf(
                        "Bash(rg:*)",
                        "Bash(grep:*)",
                        "Bash(find:*)",
                        "Bash(ls:*)",
                        "Bash(sed:*)",
                        "Bash(rm:*)",
                        "Bash(cat:*)",
                        "Bash(head:*)",
                        "Bash(tail:*)",
                        "Bash(diff:*)",
                        "Bash(du:*)",
                        "Bash(file:*)",
                        "Bash(sort:*)",
                        "Bash(stat:*)",
                        "Bash(tree:*)",
                        "Bash(uniq:*)",
                        "Bash(wc:*)",
                        "Bash(whereis:*)",
                        "Bash(which:*)",
                        "Bash(less:*)",
                        "Bash(more:*)",
                    )
                ),
                subagents = SubagentDefaults.defaults()
            )
        }
    }

    @Serializable
    data class Permissions(
        val allow: List<String>
    )
}

@Serializable
data class ProxyAISubagent(
    val id: Int,
    val title: String,
    val objective: String,
    val tools: List<String>
)
