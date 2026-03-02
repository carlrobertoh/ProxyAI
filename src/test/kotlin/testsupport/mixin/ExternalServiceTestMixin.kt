package testsupport.mixin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.core.type.TypeReference
import ee.carlrobert.codegpt.util.JsonMapper
import testsupport.http.RequestEntity
import testsupport.http.ResponseEntity
import testsupport.http.exchange.BasicHttpExchange
import testsupport.http.exchange.NdJsonStreamHttpExchange
import testsupport.http.exchange.StreamHttpExchange
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface ExternalServiceTestMixin {

    fun expectOpenAI(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.OPENAI, exchange)
    }

    fun expectOpenAI(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.OPENAI, exchange)
    }

    fun expectCodeGPT(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.PROXYAI, exchange)
    }

    fun expectOllama(exchange: NdJsonStreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.OLLAMA, exchange)
    }

    fun expectOllama(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.OLLAMA, exchange)
    }

    fun expectLlama(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.LLAMA_CPP, exchange)
    }

    fun expectGoogle(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.GOOGLE, exchange)
    }

    fun expectInception(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.INCEPTION, exchange)
    }

    fun expectInception(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.INCEPTION, exchange)
    }

    fun expectAnthropic(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.ANTHROPIC, exchange)
    }

    fun expectMistral(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.MISTRAL, exchange)
    }

    fun expectMistral(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.MISTRAL, exchange)
    }

    fun expectCustomOpenAI(exchange: BasicHttpExchange) {
        ExternalServiceMockServer.expect(Service.CUSTOM_OPENAI, exchange)
    }

    fun expectCustomOpenAI(exchange: StreamHttpExchange) {
        ExternalServiceMockServer.expect(Service.CUSTOM_OPENAI, exchange)
    }

    companion object {
        @JvmStatic
        fun init() {
            ExternalServiceMockServer.init()
        }

        @JvmStatic
        fun clearAll() {
            ExternalServiceMockServer.clearAll()
        }
    }
}

private enum class Service {
    OPENAI,
    PROXYAI,
    OLLAMA,
    LLAMA_CPP,
    GOOGLE,
    INCEPTION,
    ANTHROPIC,
    MISTRAL,
    CUSTOM_OPENAI,
}

private object ExternalServiceMockServer {

    private data class ServerState(
        val server: HttpServer,
        val expectations: ConcurrentLinkedQueue<Any> = ConcurrentLinkedQueue(),
    )

    private val servers = ConcurrentHashMap<Service, ServerState>()

    fun init() {
        if (servers.isNotEmpty()) {
            return
        }
        Service.entries.forEach { service ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val state = ServerState(server)
            server.createContext("/") { exchange ->
                handleRequest(service, state, exchange)
            }
            server.start()
            servers[service] = state
            System.setProperty(baseUrlProperty(service), "http://127.0.0.1:${server.address.port}")
        }
    }

    fun clearAll() {
        servers.values.forEach { it.expectations.clear() }
    }

    fun expect(service: Service, exchange: Any) {
        init()
        servers.getValue(service).expectations.add(exchange)
    }

    private fun handleRequest(service: Service, state: ServerState, exchange: HttpExchange) {
        runCatching {
            val expectation = state.expectations.poll()
                ?: error("No expectation configured for $service ${exchange.requestMethod} ${exchange.requestURI.path}")
            val request = toRequestEntity(exchange)
            when (expectation) {
                is BasicHttpExchange -> {
                    val response = expectation.exchange(request)
                    respondWithJson(exchange, response)
                }

                is StreamHttpExchange -> {
                    val chunks = expectation.exchange(request)
                    respondAsSse(exchange, chunks)
                }

                is NdJsonStreamHttpExchange -> {
                    val chunks = expectation.exchange(request)
                    respondAsNdJson(exchange, chunks)
                }

                else -> error("Unsupported expectation type: ${expectation::class.java.name}")
            }
        }.onFailure { throwable ->
            val body = (throwable.message ?: "Request handling failed")
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("text/plain")
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { out -> out.write(body) }
        }
    }

    private fun toRequestEntity(exchange: HttpExchange): RequestEntity {
        val requestBody = exchange.requestBody.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
        val body = if (requestBody.isBlank()) {
            emptyMap()
        } else {
            JsonMapper.mapper.readValue(requestBody, object : TypeReference<Map<String, Any?>>() {})
        }
        return RequestEntity(
            method = exchange.requestMethod,
            uri = exchange.requestURI,
            body = body,
        )
    }

    private fun respondWithJson(exchange: HttpExchange, response: ResponseEntity) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf(response.contentType)
        exchange.sendResponseHeaders(response.statusCode, bytes.size.toLong())
        exchange.responseBody.use { out -> out.write(bytes) }
    }

    private fun respondAsSse(exchange: HttpExchange, chunks: List<String>) {
        exchange.responseHeaders["Content-Type"] = listOf("text/event-stream")
        exchange.responseHeaders["Cache-Control"] = listOf("no-cache")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            chunks.forEach { chunk ->
                writer.write("data: ")
                writer.write(chunk)
                writer.write("\n\n")
                writer.flush()
            }
        }
    }

    private fun respondAsNdJson(exchange: HttpExchange, chunks: List<String>) {
        exchange.responseHeaders["Content-Type"] = listOf("application/x-ndjson")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            chunks.forEach { chunk ->
                writer.write(chunk)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun baseUrlProperty(service: Service): String {
        return when (service) {
            Service.OPENAI -> "openai.baseUrl"
            Service.PROXYAI -> "proxyai.baseUrl"
            Service.OLLAMA -> "ollama.baseUrl"
            Service.LLAMA_CPP -> "llama.baseUrl"
            Service.GOOGLE -> "google.baseUrl"
            Service.INCEPTION -> "inception.baseUrl"
            Service.ANTHROPIC -> "anthropic.baseUrl"
            Service.MISTRAL -> "mistral.baseUrl"
            Service.CUSTOM_OPENAI -> "customOpenAI.baseUrl"
        }
    }
}
