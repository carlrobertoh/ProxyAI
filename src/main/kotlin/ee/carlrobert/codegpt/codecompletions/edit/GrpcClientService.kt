package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.util.net.ssl.CertificateManager
import ee.carlrobert.codegpt.codecompletions.CodeCompletionEventListener
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.RecentlyViewedFilesUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.service.*
import io.grpc.ManagedChannel
import io.grpc.Context
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.ChannelOption
import kotlinx.coroutines.channels.ProducerScope
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class GrpcClientService(private val project: Project) : Disposable {

    private var channel: ManagedChannel? = null
    private var codeCompletionStub: CodeCompletionServiceImplGrpc.CodeCompletionServiceImplStub? =
        null
    private var codeCompletionObserver: CodeCompletionStreamObserver? = null
    private var nextEditStub: NextEditServiceImplGrpc.NextEditServiceImplStub? = null
    private var nextEditStreamObserver: NextEditStreamObserver? = null
    private var codeCompletionContext: Context.CancellableContext? = null
    private var nextEditContext: Context.CancellableContext? = null

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
        codeCompletionObserver = CodeCompletionStreamObserver(request.editor, channel, eventListener)
        codeCompletionContext?.cancel(null)
        val ctx = Context.current().withCancellation()
        codeCompletionContext = ctx
        val prev = ctx.attach()
        try {
            codeCompletionStub
                ?.withDeadlineAfter(10, TimeUnit.SECONDS)
                ?.getCodeCompletion(grpcRequest, codeCompletionObserver)
        } finally {
            ctx.detach(prev)
        }
    }

    @Synchronized
    fun cancelCodeCompletion() {
        codeCompletionContext?.cancel(null)
        codeCompletionContext = null
        codeCompletionObserver = null
    }

    fun getNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int,
        addToQueue: Boolean = false,
    ) {
        ensureNextEditConnection()

        val request = createNextEditGrpcRequest(editor, fileContent, caretOffset)
        nextEditStreamObserver = NextEditStreamObserver(editor, addToQueue) { refreshConnection() }
        nextEditContext?.cancel(null)

        val ctx = Context.current().withCancellation()
        nextEditContext = ctx
        val prev = ctx.attach()
        try {
            nextEditStub
                ?.withDeadlineAfter(10, TimeUnit.SECONDS)
                ?.nextEdit(request, nextEditStreamObserver)
        } finally {
            ctx.detach(prev)
        }
    }

    @Synchronized
    fun cancelNextEdit() {
        nextEditContext?.cancel(null)
        nextEditContext = null
        nextEditStreamObserver = null
    }

    @Synchronized
    fun acceptEdit(responseId: String, oldHunk: String, newHunk: String, cursorPosition: Int? = null) {
        ensureActiveChannel()

        NextEditServiceImplGrpc
            .newBlockingStub(channel)
            .acceptEdit(
                AcceptEditRequest.newBuilder()
                    .setResponseId(responseId)
                    .setOldHunk(oldHunk)
                    .setNewHunk(newHunk)
                    .apply { cursorPosition?.let { setCursorPosition(it) } }
                    .build()
            )
    }

    @Synchronized
    fun acceptCodeCompletion(responseId: String, acceptedCompletion: String) {
        ensureActiveChannel()

        CodeCompletionServiceImplGrpc
            .newBlockingStub(channel)
            .acceptCodeCompletion(
                AcceptCodeCompletionRequest.newBuilder()
                    .setResponseId(responseId)
                    .setAcceptedCompletion(acceptedCompletion)
                    .build()
            )
    }

    @Synchronized
    fun refreshConnection() {
        codeCompletionContext?.cancel(null)
        codeCompletionContext = null
        nextEditContext?.cancel(null)
        nextEditContext = null
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
            .setPluginVersion(CodeGPTPlugin.getVersion())
            .build()
    }

    private fun createNextEditGrpcRequest(
        editor: Editor,
        fileContent: String,
        caretOffset: Int
    ): NextEditRequest {
        val recentlyViewedPairs =
            RecentlyViewedFilesUtil.orderedFiles(project, editor.virtualFile, 3)
                .map { it.path to FileUtil.readContent(it) }
        return NextEditRequest.newBuilder()
            .setFileName(editor.virtualFile.name)
            .setFileContent(fileContent)
            .setGitDiff(GitUtil.getCurrentChanges(project) ?: "")
            .setCursorPosition(caretOffset)
            .putAllRecentlyViewedFiles(recentlyViewedPairs.toMap())
            .setPluginVersion(CodeGPTPlugin.getVersion())
            .build()
    }

    private fun createChannel(): ManagedChannel = NettyChannelBuilder.forAddress(HOST, PORT)
        .useTransportSecurity()
        .sslContext(
            GrpcSslContexts.forClient()
                .trustManager(CertificateManager.getInstance().trustManager)
                .build()
        )
        .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
        .keepAliveTime(2, TimeUnit.MINUTES)
        .keepAliveTimeout(20, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(false)
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
        codeCompletionContext?.cancel(null)
        codeCompletionContext = null
        nextEditContext?.cancel(null)
        nextEditContext = null
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
