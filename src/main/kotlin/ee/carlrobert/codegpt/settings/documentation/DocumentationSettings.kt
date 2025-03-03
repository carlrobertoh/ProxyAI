package ee.carlrobert.codegpt.settings.documentation

import com.intellij.openapi.components.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Service
@State(
    name = "CodeGPT_DocumentationSettings",
    storages = [Storage("CodeGPT_DocumentationSettings.xml")]
)
class DocumentationSettings :
    SimplePersistentStateComponent<DocumentationSettingsState>(DocumentationSettingsState()) {

    fun updateLastUsedDateTime(url: String) {
        state.documentations
            .find { it.url == url }
            ?.run {
                lastUsedDateTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            }
    }
}

class DocumentationSettingsState : BaseState() {
    var documentations by list<DocumentationDetailsState>()

    init {
        documentations.addAll(DEFAULT_DOCUMENTATIONS)
    }
}

class DocumentationDetailsState : BaseState() {
    var name by string("ProxyAI Docs")
    var url by string("https://docs.tryproxy.io")
    var lastUsedDateTime by string()
}

private val DEFAULT_DOCUMENTATIONS = mutableListOf(
    getDocState("Spring Framework API", "https://docs.spring.io/spring-framework/docs/current/javadoc-api/"),
    getDocState("Astro Runtime API", "https://docs.astro.build/en/reference/api-reference/"),
    getDocState("Flask API", "https://flask.palletsprojects.com/en/3.0.x/api/"),
    getDocState("Flutter API", "https://api.flutter.dev/"),
    getDocState("IPFS Kubo CLI", "https://docs.ipfs.tech/reference/kubo/cli/#ipfs"),
    getDocState("Kotlin Coding Conventions", "https://kotlinlang.org/docs/coding-conventions.html"),
    getDocState(
        "Next.js Authentication",
        "https://nextjs.org/docs/app/building-your-application/authentication"
    ),
    getDocState("SolidJS Documentation", "https://www.solidjs.com/docs"),
    getDocState("SvelteKit Modules", "https://kit.svelte.dev/docs/modules"),
    getDocState("SwiftUI Updates", "https://developer.apple.com/documentation/updates/swiftui"),
    getDocState("Zapier CLI Documentation", "https://platform.zapier.com/reference/cli-docs"),
)

private fun getDocState(name: String, url: String): DocumentationDetailsState {
    return DocumentationDetailsState().apply {
        this.name = name
        this.url = url
    }
}
