package ee.carlrobert.codegpt.agent.clients

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.ssl.CertificateManager
import ee.carlrobert.codegpt.settings.advanced.AdvancedSettings
import ee.carlrobert.codegpt.settings.advanced.AdvancedSettingsState
import io.ktor.client.*
import io.ktor.client.engine.apache5.*
import io.ktor.client.plugins.*
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner
import org.apache.hc.core5.http.HttpHost
import java.net.Proxy

/**
 * Provides configured Ktor HttpClient instances with proxy support for Agent mode.
 */
object HttpClientProvider {

    private val logger = Logger.getInstance(HttpClientProvider::class.java)

    /**
     * Creates a Ktor HttpClient configured with proxy settings from AdvancedSettings.
     * Supports both HTTP and SOCKS proxies with optional authentication.
     */
    fun createHttpClient(): HttpClient {
        val advancedSettings = AdvancedSettings.getCurrentState()
        return HttpClient(Apache5) {
            engine {
                configureProxy(advancedSettings)
            }

            install(HttpTimeout) {
                connectTimeoutMillis = advancedSettings.connectTimeout.toLong() * 1000
                requestTimeoutMillis = advancedSettings.readTimeout.toLong() * 1000
                socketTimeoutMillis = advancedSettings.readTimeout.toLong() * 1000
            }
        }
    }

    private fun Apache5EngineConfig.configureProxy(settings: AdvancedSettingsState) {
        val proxyHost = settings.proxyHost
        val proxyPort = settings.proxyPort

        if (proxyHost.isBlank() || proxyPort == 0) {
            logger.info("No proxy configured for Agent mode")
            customizeClient {
                sslContext = CertificateManager.getInstance().sslContext
            }
            return
        }

        logger.info("Configuring Agent mode with ${settings.proxyType} proxy: $proxyHost:$proxyPort")

        val proxy = createProxy(settings) ?: run {
            logger.warn("Invalid proxy type configured: ${settings.proxyType}")
            customizeClient {
                sslContext = CertificateManager.getInstance().sslContext
            }
            return
        }

        customizeClient {
            sslContext = CertificateManager.getInstance().sslContext

            setRoutePlanner(DefaultProxyRoutePlanner(proxy))

            if (settings.isProxyAuthSelected) {
                configureProxyAuthentication(settings, proxy, this)
            }
        }
    }

    private fun createProxy(settings: AdvancedSettingsState): HttpHost? {
        return when (settings.proxyType) {
            Proxy.Type.HTTP -> HttpHost("http", settings.proxyHost, settings.proxyPort)
            Proxy.Type.SOCKS -> HttpHost("socks", settings.proxyHost, settings.proxyPort)
            else -> null
        }
    }

    private fun configureProxyAuthentication(
        settings: AdvancedSettingsState,
        proxy: HttpHost,
        clientBuilder: HttpAsyncClientBuilder
    ) {
        val username = settings.proxyUsername ?: ""
        if (username.isNotBlank()) {
            logger.info("Configuring proxy authentication for user: $username")

            val credentialsProvider = BasicCredentialsProvider()
            credentialsProvider.setCredentials(
                AuthScope(proxy),
                UsernamePasswordCredentials(
                    username,
                    settings.proxyPassword?.toCharArray() ?: charArrayOf()
                )
            )

            clientBuilder.setDefaultCredentialsProvider(credentialsProvider)
        }
    }
}
