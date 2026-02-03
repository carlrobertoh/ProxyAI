package ee.carlrobert.codegpt.settings

object ToolPermissionPolicy {

    enum class Decision {
        DENY,
        ASK,
        ALLOW,
        NONE
    }

    data class PermissionLists(
        val allow: List<String> = emptyList(),
        val ask: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    fun evaluate(
        permissions: PermissionLists,
        toolName: String,
        targets: List<String>
    ): Decision {
        if (firstMatch(permissions.deny, toolName, targets) != null) return Decision.DENY
        if (firstMatch(permissions.ask, toolName, targets) != null) return Decision.ASK
        if (firstMatch(permissions.allow, toolName, targets) != null) return Decision.ALLOW
        return Decision.NONE
    }

    private fun parseRule(raw: String): PermissionRule? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val open = raw.indexOf('(')
        if (open < 0) {
            return PermissionRule(raw.trim(), null)
        }
        val close = raw.lastIndexOf(')')
        if (open !in 1..close) return null

        val toolName = raw.substring(0, open).trim()
        val inner = raw.substring(open + 1, close).trim()
        if (toolName.isEmpty()) return null
        return PermissionRule(toolName, inner.ifEmpty { "*" })
    }

    private fun firstMatch(rules: List<String>, toolName: String, targets: List<String>): PermissionRule? {
        val parsed = rules.mapNotNull { parseRule(it) }
        return parsed.firstOrNull { rule ->
            rule.toolName == toolName && targets.any { target -> matches(rule, target) }
        }
    }

    private fun matches(rule: PermissionRule, target: String): Boolean {
        val specifier = rule.specifier ?: return true
        if (specifier == "*") return true
        return if (specifier.contains('*')) {
            wildcardMatch(specifier, target)
        } else {
            target == specifier
        }
    }

    private fun wildcardMatch(pattern: String, target: String): Boolean {
        val regexPattern = pattern
            .split('*')
            .joinToString(".*") { Regex.escape(it) }
        return Regex("^$regexPattern$").matches(target)
    }

    private data class PermissionRule(val toolName: String, val specifier: String?)
}
