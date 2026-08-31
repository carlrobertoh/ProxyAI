package ee.carlrobert.codegpt.codecompletions

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import com.intellij.openapi.components.service
import com.intellij.util.net.ssl.CertificateManager
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CustomServiceApiKeyById
import ee.carlrobert.codegpt.credentials.CredentialsStore.setCredential
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import org.assertj.core.api.Assertions.assertThat
import testsupport.IntegrationTest
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class CodeCompletionCertificateIntegrationTest : IntegrationTest() {

    private var httpsServer: HttpsServer? = null
    private var trustedCertificate: X509Certificate? = null
    private var certificateDirectory: Path? = null

    override fun tearDown() {
        try {
            httpsServer?.stop(0)
            runCatching {
                trustedCertificate?.let {
                    CertificateManager.getInstance().getCustomTrustManager().removeCertificate(it)
                }
            }
            runCatching {
                certificateDirectory?.let(::deleteRecursively)
            }
        } finally {
            httpsServer = null
            trustedCertificate = null
            certificateDirectory = null
            super.tearDown()
        }
    }

    fun `test custom openai completion and test connection use ide trusted certificate`() {
        val completionUrl = startCompletionServer()
        val customService = CustomServiceSettingsState()
        configureCompletionSettings(customService.codeCompletionSettings, completionUrl)

        service<CustomServicesSettings>().state.services.clear()
        service<CustomServicesSettings>().state.services.add(customService)
        setCredential(CustomServiceApiKeyById(requireNotNull(customService.id)), "TEST_API_KEY")
        service<ModelSettings>().setModel(
            FeatureType.CODE_COMPLETION,
            customService.id,
            ServiceType.CUSTOM_OPENAI
        )

        val completionListener = RecordingListener()
        val completionRequest = project.service<CodeCompletionService>().getCodeCompletionAsync(
            InfillRequest.Builder("prefix", "suffix", 0).build(),
            completionListener
        )
        try {
            waitExpecting { completionListener.completed != null || completionListener.error != null }
        } finally {
            completionRequest.cancel()
        }

        assertThat(completionListener.error).isNull()
        assertThat(completionListener.completed).isEqualTo("accepted completion")

        val connectionListener = RecordingListener()
        val connectionRequest = project.service<CodeCompletionService>().testCustomOpenAIConnectionAsync(
            settings = customService.codeCompletionSettings,
            apiKey = "TEST_API_KEY",
            eventListener = connectionListener
        )
        try {
            waitExpecting { connectionListener.completed != null || connectionListener.error != null }
        } finally {
            connectionRequest.cancel()
        }

        assertThat(connectionListener.error).isNull()
        assertThat(connectionListener.completed).isEqualTo("accepted completion")
    }

    private fun startCompletionServer(): String {
        val directory = Files.createTempDirectory("proxyai-issue-1000-")
        certificateDirectory = directory
        val password = "changeit"
        val keyStore = directory.resolve("server.p12")
        val certificateFile = directory.resolve("server.crt")
        val commonName = "proxyai-issue-1000-${UUID.randomUUID()}"

        runKeytool(
            "-genkeypair",
            "-alias", "server",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-storetype", "PKCS12",
            "-keystore", keyStore.toString(),
            "-storepass", password,
            "-keypass", password,
            "-dname", "CN=$commonName",
            "-noprompt"
        )
        runKeytool(
            "-exportcert",
            "-alias", "server",
            "-keystore", keyStore.toString(),
            "-storepass", password,
            "-rfc",
            "-file", certificateFile.toString()
        )

        val certificate = Files.newInputStream(certificateFile).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val subjectAlternativeNames = certificate.subjectAlternativeNames
        assertThat(subjectAlternativeNames == null || subjectAlternativeNames.isEmpty()).isTrue()
        assertThat(CertificateManager.getInstance().getCustomTrustManager().addCertificate(certificate)).isTrue()
        trustedCertificate = certificate

        val serverKeyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(keyStore).use { input ->
            serverKeyStore.load(input, password.toCharArray())
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(serverKeyStore, password.toCharArray())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)

        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = object : HttpsConfigurator(sslContext) {
            override fun configure(parameters: HttpsParameters) {
                parameters.sslParameters = sslContext.defaultSSLParameters
            }
        }
        server.createContext("/v1/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val response = """{"choices":[{"text":"accepted completion"}]}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders["Content-Type"] = listOf("application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        httpsServer = server
        return "https://127.0.0.1:${server.address.port}/v1/completions"
    }

    private fun configureCompletionSettings(
        settings: CustomServiceCodeCompletionSettingsState,
        url: String
    ) {
        settings.url = url
        settings.headers.clear()
        settings.headers["Authorization"] = "Bearer \$CUSTOM_SERVICE_API_KEY"
        settings.body.clear()
        settings.body["prompt"] = "\$PREFIX"
        settings.body["suffix"] = "\$SUFFIX"
        settings.body["model"] = "custom-code-model"
        settings.body["max_tokens"] = 16
    }

    private fun runKeytool(vararg arguments: String) {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) {
            "keytool.exe"
        } else {
            "keytool"
        }
        val process = ProcessBuilder(
            listOf(Path.of(System.getProperty("java.home"), "bin", executable).toString()) + arguments.toList()
        ).redirectErrorStream(true).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("keytool timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            throw AssertionError("keytool failed: $output")
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.notExists(path)) {
            return
        }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class RecordingListener : CompletionStreamEventListener {
        @Volatile
        var completed: String? = null

        @Volatile
        var error: Throwable? = null

        override fun onMessage(message: String) = Unit

        override fun onComplete(messageBuilder: StringBuilder) {
            completed = messageBuilder.toString()
        }

        override fun onCancelled(messageBuilder: StringBuilder) {
            completed = messageBuilder.toString()
        }

        override fun onError(error: CompletionError, ex: Throwable) {
            this.error = ex
        }
    }
}
