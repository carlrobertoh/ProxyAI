package ee.carlrobert.codegpt.agent

import com.sun.net.httpserver.HttpServer
import ee.carlrobert.codegpt.agent.tools.WebFetchTool
import ee.carlrobert.codegpt.settings.hooks.HookManager
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.net.InetSocketAddress

class WebFetchToolTest : IntegrationTest() {

    fun testWebFetchConvertsHtmlToMarkdown() {
        withServer(
            mapOf(
                "/docs" to Response(
                    contentType = "text/html; charset=utf-8",
                    body = """
                        <html>
                          <head><title>ProxyAI Docs</title></head>
                          <body>
                            <main>
                              <h1>WebFetch Tool</h1>
                              <p>Turns HTML into Markdown.</p>
                            </main>
                          </body>
                        </html>
                    """.trimIndent()
                )
            )
        ) { baseUrl ->
            val result = runBlocking {
                WebFetchTool(
                    workingDirectory = project.basePath ?: "",
                    hookManager = HookManager(project)
                ).execute(WebFetchTool.Args(url = "$baseUrl/docs"))
            }

            assertThat(result.error).isNull()
            assertThat(result.title).isEqualTo("ProxyAI Docs")
            assertThat(result.statusCode).isEqualTo(200)
            assertThat(result.markdown).contains("WebFetch Tool")
            assertThat(result.markdown).contains("Turns HTML into Markdown.")
        }
    }

    fun testWebFetchRespectsSelector() {
        withServer(
            mapOf(
                "/page" to Response(
                    contentType = "text/html; charset=utf-8",
                    body = """
                        <html>
                          <head><title>Selector Test</title></head>
                          <body>
                            <main>
                              <article>
                                <h2>Main Article</h2>
                                <p>Important content</p>
                              </article>
                            </main>
                            <aside>Sidebar content</aside>
                          </body>
                        </html>
                    """.trimIndent()
                )
            )
        ) { baseUrl ->
            val result = runBlocking {
                WebFetchTool(
                    workingDirectory = project.basePath ?: "",
                    hookManager = HookManager(project)
                ).execute(
                    WebFetchTool.Args(
                        url = "$baseUrl/page",
                        selector = "article"
                    )
                )
            }

            assertThat(result.error).isNull()
            assertThat(result.usedSelector).isEqualTo("article")
            assertThat(result.markdown).contains("Main Article")
            assertThat(result.markdown).doesNotContain("Sidebar content")
        }
    }

    fun testWebFetchReturnsErrorForNonHtmlContent() {
        withServer(
            mapOf(
                "/api" to Response(
                    contentType = "application/json",
                    body = """{"ok":true}"""
                )
            )
        ) { baseUrl ->
            val result = runBlocking {
                WebFetchTool(
                    workingDirectory = project.basePath ?: "",
                    hookManager = HookManager(project)
                ).execute(WebFetchTool.Args(url = "$baseUrl/api"))
            }

            assertThat(result.error).contains("Unsupported content type")
            assertThat(result.contentType).contains("application/json")
        }
    }

    fun testWebFetchSupportsOffsetAndLimitPaging() {
        withServer(
            mapOf(
                "/long" to Response(
                    contentType = "text/html; charset=utf-8",
                    body = """
                        <html>
                          <head><title>Paging Test</title></head>
                          <body>
                            <main>
                              ${(1..30).joinToString("\n") { "<p>Line $it</p>" }}
                            </main>
                          </body>
                        </html>
                    """.trimIndent()
                )
            )
        ) { baseUrl ->
            val tool = createTool()
            val result = runBlocking {
                tool.execute(
                    WebFetchTool.Args(
                        url = "$baseUrl/long",
                        offset = 2,
                        limit = 4
                    )
                )
            }

            assertThat(result.error).isNull()
            assertThat(result.truncated).isTrue()
            assertThat(result.startLine).isEqualTo(2)
            assertThat(result.endLine).isEqualTo(5)
            assertThat(result.markdown.lines()).hasSize(4)
            assertThat(tool.encodeResultToString(result)).contains("Note: Content truncated")
        }
    }

    fun testWebFetchRejectsInvalidPagingParams() {
        val tool = createTool()

        val invalidOffset = runBlocking {
            tool.execute(WebFetchTool.Args(url = "https://example.com", offset = 0))
        }
        val invalidLimit = runBlocking {
            tool.execute(WebFetchTool.Args(url = "https://example.com", limit = 0))
        }

        assertThat(invalidOffset.error).contains("Invalid offset value")
        assertThat(invalidLimit.error).contains("Invalid limit value")
    }

    private fun createTool(): WebFetchTool {
        return WebFetchTool(
            workingDirectory = project.basePath ?: "",
            hookManager = HookManager(project)
        )
    }

    private data class Response(
        val contentType: String,
        val body: String,
        val status: Int = 200
    )

    private fun withServer(routes: Map<String, Response>, block: (baseUrl: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, response) ->
            server.createContext(path) { exchange ->
                exchange.responseHeaders.set("Content-Type", response.contentType)
                val bytes = response.body.toByteArray()
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(bytes)
                }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
