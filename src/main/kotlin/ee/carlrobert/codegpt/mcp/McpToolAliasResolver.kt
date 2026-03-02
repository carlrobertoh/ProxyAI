package ee.carlrobert.codegpt.mcp

import java.util.Locale

internal object McpToolAliasResolver {

    fun <T> resolve(
        items: List<T>,
        toolName: (T) -> String,
        scopeName: (T) -> String
    ): List<Pair<T, String>> {
        val nameCounts = items
            .groupingBy { toolName(it).lowercase(Locale.ROOT) }
            .eachCount()
        val usedAliases = mutableSetOf<String>()

        return items.map { item ->
            val base = if ((nameCounts[toolName(item).lowercase(Locale.ROOT)] ?: 0) > 1) {
                "${toolName(item)}_${normalize(scopeName(item))}"
            } else {
                toolName(item)
            }

            var alias = base
            var index = 2
            while (!usedAliases.add(alias)) {
                alias = "${base}_$index"
                index++
            }

            item to alias
        }
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "server" }
    }
}
