package ee.carlrobert.codegpt.agent

enum class SubagentTool(val id: String, val displayName: String, val isWrite: Boolean) {
    READ("read", "Read", false),
    TODO_WRITE("todowrite", "TodoWrite", false),
    INTELLIJ_SEARCH("intellijsearch", "IntelliJSearch", false),
    WEB_SEARCH("websearch", "WebSearch", false),
    RESOLVE_LIBRARY_ID("resolvelibraryid", "ResolveLibraryId", false),
    GET_LIBRARY_DOCS("getlibrarydocs", "GetLibraryDocs", false),
    BASH_OUTPUT("bashoutput", "BashOutput", false),
    KILL_SHELL("killshell", "KillShell", false),
    EDIT("edit", "Edit", true),
    WRITE("write", "Write", true),
    BASH("bash", "Bash", true),
    EXIT("exit", "Exit", false);

    companion object {
        val readOnly: List<SubagentTool> = entries.filterNot { it.isWrite }
        val write: List<SubagentTool> = entries.filter { it.isWrite }

        fun parse(values: Collection<String>): Set<SubagentTool> {
            return values.mapNotNull { fromString(it) }.toSet()
        }

        fun toStoredValues(tools: Collection<SubagentTool>): List<String> {
            val selected = tools.toSet()
            return entries.filter { it in selected }.map { it.id }
        }

        fun fromString(value: String): SubagentTool? {
            val key = normalize(value)
            return entries.firstOrNull { normalize(it.id) == key || normalize(it.displayName) == key }
        }

        private fun normalize(value: String): String {
            return value.lowercase().filter { it.isLetterOrDigit() }
        }
    }
}
