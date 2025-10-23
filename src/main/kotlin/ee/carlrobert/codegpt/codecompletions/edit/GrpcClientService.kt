package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.util.net.ssl.CertificateManager
import com.jetbrains.rd.util.UUID
import ee.carlrobert.codegpt.codecompletions.CodeCompletionEventListener
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.telemetry.core.configuration.TelemetryConfiguration
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.service.*
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.ChannelOption
import kotlinx.coroutines.channels.ProducerScope
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class GrpcClientService(private val project: Project) : Disposable {

    private var channel: ManagedChannel? = null
    private var codeCompletionStub: CodeCompletionServiceImplGrpc.CodeCompletionServiceImplStub? = null
    private var codeCompletionObserver: CodeCompletionStreamObserver? = null
    private var nextEditStub: NextEditServiceImplGrpc.NextEditServiceImplStub? = null
    private var nextEditStreamObserver: NextEditStreamObserver? = null

    companion object {
        private const val HOST = "grpc.tryproxy.io"
        private const val PORT = 9090
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        private val logger = thisLogger()
    }

    fun getCodeCompletionAsync(
        eventListener: CodeCompletionEventListener,
        request: InlineCompletionRequest,
        channel: ProducerScope<InlineCompletionElement>
    ) {
        ensureCodeCompletionConnection()

        val grpcRequest = createCodeCompletionGrpcRequest(request)
        codeCompletionObserver = CodeCompletionStreamObserver(channel, eventListener)
        codeCompletionStub?.getCodeCompletion(grpcRequest, codeCompletionObserver)
    }

    fun getNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int,
        addToQueue: Boolean = false,
    ) {
        if (service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT) != ServiceType.PROXYAI
            || !service<CodeGPTServiceSettings>().state.nextEditsEnabled
        ) {
            return
        }

        ensureNextEditConnection()

        val request = createNextEditGrpcRequest(editor, fileContent, caretOffset)
        nextEditStreamObserver = NextEditStreamObserver(editor, addToQueue) { dispose() }
        nextEditStub?.nextEdit(request, nextEditStreamObserver)
    }

    fun acceptEdit(responseId: UUID, acceptedEdit: String) {
        if (service<ModelSelectionService>().getServiceForFeature(FeatureType.CODE_COMPLETION) != ServiceType.PROXYAI
            || !TelemetryConfiguration.getInstance().isCompletionTelemetryEnabled
        ) {
            return
        }

        NextEditServiceImplGrpc
            .newBlockingStub(channel)
            .acceptEdit(
                AcceptEditRequest.newBuilder()
                    .setResponseId(responseId.toString())
                    .setAcceptedEdit(acceptedEdit)
                    .build()
            )
    }

    @Synchronized
    fun refreshConnection() {
        channel?.let {
            if (!it.isShutdown) {
                try {
                    it.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    logger.info("Existing gRPC connection closed for refresh")
                } catch (e: InterruptedException) {
                    logger.warn("Interrupted while shutting down gRPC channel for refresh", e)
                    Thread.currentThread().interrupt()
                } finally {
                    if (!it.isTerminated) {
                        it.shutdownNow()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun ensureCodeCompletionConnection() {
        ensureActiveChannel()

        if (codeCompletionStub == null) {
            codeCompletionStub = CodeCompletionServiceImplGrpc.newStub(channel)
                .withCallCredentials(createCallCredentials())
                .withDeadlineAfter(300, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    private fun ensureNextEditConnection() {
        ensureActiveChannel()

        if (nextEditStub == null) {
            nextEditStub = NextEditServiceImplGrpc.newStub(channel)
                .withCallCredentials(createCallCredentials())
                .withDeadlineAfter(300, TimeUnit.SECONDS)
        }
    }

    private fun createCodeCompletionGrpcRequest(request: InlineCompletionRequest): GrpcCodeCompletionRequest {
        val editor = request.editor
        return GrpcCodeCompletionRequest.newBuilder()
            .setModel(
                ModelSelectionService.getInstance().getModelForFeature(FeatureType.CODE_COMPLETION)
            )
            .setFilePath(editor.virtualFile.path)
            .setFileContent(editor.document.text)
            .setGitDiff(GitUtil.getCurrentChanges(project) ?: "")
            .setCursorPosition(runReadAction { editor.caretModel.offset })
            .setEnableTelemetry(TelemetryConfiguration.getInstance().isCompletionTelemetryEnabled)
            .build()
    }

    private fun createNextEditGrpcRequest(editor: Editor, fileContent: String, caretOffset: Int) =
        NextEditRequest.newBuilder()
            .setFileName(editor.virtualFile.name)
            .setFileContent(fileContent)
            .setGitDiff(GitUtil.getCurrentChanges(project) ?: "")
            .setCursorPosition(caretOffset)
            .setEnableTelemetry(TelemetryConfiguration.getInstance().isCompletionTelemetryEnabled)
            .build()

    private fun createChannel(): ManagedChannel = NettyChannelBuilder.forAddress(HOST, PORT)
        .useTransportSecurity()
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(CertificateManager.getInstance().trustManager)
                .build()
        )
        .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
        .keepAliveTime(30, TimeUnit.SECONDS)
        .keepAliveTimeout(10, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .idleTimeout(5, TimeUnit.MINUTES)
        .maxInboundMessageSize(32 * 1024 * 1024)
        .build()

    private fun ensureActiveChannel() {
        if (channel == null || channel?.isShutdown == true || channel?.isTerminated == true) {
            try {
                channel = createChannel()
                codeCompletionStub = null
                nextEditStub = null
                logger.info("gRPC connection established")
            } catch (e: Exception) {
                logger.error("Failed to establish gRPC connection", e)
                throw e
            }
        }
    }

    private fun createCallCredentials() =
        GrpcCallCredentials(CredentialsStore.getCredential(CodeGptApiKey) ?: "")

    override fun dispose() {
        channel?.let { ch ->
            if (!ch.isShutdown) {
                try {
                    ch.shutdown().awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    logger.info("gRPC connection closed")
                } catch (e: InterruptedException) {
                    logger.warn("Interrupted while shutting down gRPC channel", e)
                    Thread.currentThread().interrupt()
                } finally {
                    if (!ch.isTerminated) {
                        ch.shutdownNow()
                    }
                }
            }
        }
        channel = null
    }
}
