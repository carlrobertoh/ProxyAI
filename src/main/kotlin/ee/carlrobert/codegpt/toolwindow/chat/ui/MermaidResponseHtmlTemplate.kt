package ee.carlrobert.codegpt.toolwindow.chat.ui

import ee.carlrobert.codegpt.util.file.FileUtil
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class MermaidThemePalette(
    val foreground: String,
    val mutedForeground: String,
    val hudBackground: String,
    val hudBorder: String,
    val buttonBackground: String,
    val buttonBackgroundHover: String,
    val buttonBorder: String,
    val error: String,
    val fontFamily: String,
    val fontSize: String,
    val mermaidTheme: String,
)

object MermaidResponseHtmlTemplate {

    private const val DIAGRAM_PLACEHOLDER = "__MERMAID_SOURCE__"
    private const val DIAGRAM_TYPE_LABEL_PLACEHOLDER = "__DIAGRAM_TYPE_LABEL__"
    private const val NODE_TARGETS_PLACEHOLDER = "__NODE_TARGETS_JSON__"
    private const val NODE_CLICK_HANDLER_PLACEHOLDER = "__NODE_CLICK_HANDLER__"
    private const val RENDER_ERROR_HANDLER_PLACEHOLDER = "__RENDER_ERROR_HANDLER__"
    private const val THEME_FOREGROUND_PLACEHOLDER = "__THEME_FOREGROUND__"
    private const val THEME_MUTED_FOREGROUND_PLACEHOLDER = "__THEME_MUTED_FOREGROUND__"
    private const val THEME_HUD_BACKGROUND_PLACEHOLDER = "__THEME_HUD_BACKGROUND__"
    private const val THEME_HUD_BORDER_PLACEHOLDER = "__THEME_HUD_BORDER__"
    private const val THEME_BUTTON_BACKGROUND_PLACEHOLDER = "__THEME_BUTTON_BACKGROUND__"
    private const val THEME_BUTTON_BACKGROUND_HOVER_PLACEHOLDER =
        "__THEME_BUTTON_BACKGROUND_HOVER__"
    private const val THEME_BUTTON_BORDER_PLACEHOLDER = "__THEME_BUTTON_BORDER__"
    private const val THEME_ERROR_PLACEHOLDER = "__THEME_ERROR__"
    private const val THEME_FONT_FAMILY_PLACEHOLDER = "__THEME_FONT_FAMILY__"
    private const val THEME_FONT_SIZE_PLACEHOLDER = "__THEME_FONT_SIZE__"
    private const val MERMAID_THEME_PLACEHOLDER = "__MERMAID_THEME__"
    private const val MERMAID_SCRIPT_URL_PLACEHOLDER = "__MERMAID_SCRIPT_URL__"

    private const val TEMPLATE_RESOURCE_PATH = "/templates/mermaid-response-panel.html"
    private const val BUNDLED_MERMAID_SCRIPT_RESOURCE_PATH = "/web/mermaid/mermaid.min.js"

    private val placeholderRegex = Regex("__[A-Z0-9_]+__")

    private val templateHtml: String by lazy {
        FileUtil.getResourceContent(TEMPLATE_RESOURCE_PATH)
            .ifBlank { error("Missing Mermaid response template at $TEMPLATE_RESOURCE_PATH") }
    }
    private val bundledMermaidScriptUrl: String by lazy {
        val stream = MermaidResponseHtmlTemplate::class.java
            .getResourceAsStream(BUNDLED_MERMAID_SCRIPT_RESOURCE_PATH)
            ?: error("Missing bundled Mermaid script at $BUNDLED_MERMAID_SCRIPT_RESOURCE_PATH")

        stream.use {
            val tempFile = Files.createTempFile("proxyai-mermaid-", ".min.js")
            tempFile.toFile().deleteOnExit()
            Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING)
            tempFile.toUri().toString()
        }
    }

    fun render(source: String, diagramTypeLabel: String, theme: MermaidThemePalette): String {
        val replacements = mapOf(
            DIAGRAM_PLACEHOLDER to toJavaScriptStringLiteral(source),
            DIAGRAM_TYPE_LABEL_PLACEHOLDER to toJavaScriptStringLiteral(diagramTypeLabel),
            NODE_TARGETS_PLACEHOLDER to "{}",
            NODE_CLICK_HANDLER_PLACEHOLDER to "void 0",
            RENDER_ERROR_HANDLER_PLACEHOLDER to "void 0",
            THEME_FOREGROUND_PLACEHOLDER to theme.foreground,
            THEME_MUTED_FOREGROUND_PLACEHOLDER to theme.mutedForeground,
            THEME_HUD_BACKGROUND_PLACEHOLDER to theme.hudBackground,
            THEME_HUD_BORDER_PLACEHOLDER to theme.hudBorder,
            THEME_BUTTON_BACKGROUND_PLACEHOLDER to theme.buttonBackground,
            THEME_BUTTON_BACKGROUND_HOVER_PLACEHOLDER to theme.buttonBackgroundHover,
            THEME_BUTTON_BORDER_PLACEHOLDER to theme.buttonBorder,
            THEME_ERROR_PLACEHOLDER to theme.error,
            THEME_FONT_FAMILY_PLACEHOLDER to theme.fontFamily,
            THEME_FONT_SIZE_PLACEHOLDER to theme.fontSize,
            MERMAID_THEME_PLACEHOLDER to theme.mermaidTheme,
            MERMAID_SCRIPT_URL_PLACEHOLDER to toJavaScriptStringLiteral(bundledMermaidScriptUrl),
        )

        return placeholderRegex.replace(templateHtml) { matchResult ->
            replacements[matchResult.value] ?: matchResult.value
        }
    }

    private fun toJavaScriptStringLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("<", "\\x3C")
            .replace(">", "\\x3E")
            .replace("&", "\\x26")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")

        return "\"$escaped\""
    }
}
