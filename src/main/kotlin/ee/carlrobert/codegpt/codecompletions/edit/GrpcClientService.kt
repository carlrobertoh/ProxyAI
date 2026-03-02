package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.util.net.ssl.CertificateManager
import ee.carlrobert.codegpt.CodeGPTPlugin
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.completions.CancellableRequest
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.RecentlyViewedFilesUtil
import ee.carlrobert.codegpt.util.file.FileUtil
import ee.carlrobert.service.*
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.netty.channel.ChannelOption
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
        private const val HOST_PROPERTY = "proxyai.grpc.host"
        private const val PORT_PROPERTY = "proxyai.grpc.port"
        private const val PLAINTEXT_PROPERTY = "proxyai.grpc.plaintext"

        private val logger = thisLogger()
    }

    fun getCodeCompletionAsync(
        request: InfillRequest,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        ensureCodeCompletionConnection()

        val editor = request.editor ?: return CancellableRequest {}
        val grpcRequest = createCodeCompletionGrpcRequest(request)
        eventListener.onOpen()
        codeCompletionObserver =
            CodeCompletionStreamObserver(editor, eventListener)
        codeCompletionContext?.cancel(null)
        val ctx = Context.current().withCancellation()
        codeCompletionContext = ctx
        val prev = ctx.attach()
        try {
            codeCompletionStub
                ?.withDeadlineAfter(2, TimeUnit.SECONDS)
                ?.getCodeCompletion(grpcRequest, codeCompletionObserver)
        } finally {
            ctx.detach(prev)
        }
        return CancellableRequest { cancelCodeCompletion() }
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
                ?.withDeadlineAfter(2, TimeUnit.SECONDS)
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
    fun acceptEdit(
        responseId: String,
        oldHunk: String,
        newHunk: String,
        cursorPosition: Int? = null
    ) {
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
        }
    }

    @Synchronized
    private fun ensureNextEditConnection() {
        ensureActiveChannel()

        if (nextEditStub == null) {
            nextEditStub = NextEditServiceImplGrpc.newStub(channel)
                .withCallCredentials(createCallCredentials())
        }
    }

    private fun createCodeCompletionGrpcRequest(request: InfillRequest): GrpcCodeCompletionRequest {
        val fileDetails = request.fileDetails ?: throw IllegalArgumentException("File details are required")
        val gitDiff = request.gitDiff ?: ""
        return GrpcCodeCompletionRequest.newBuilder()
            .setModel(
                ModelSettings.getInstance().getModelForFeature(FeatureType.CODE_COMPLETION)
            )
            .setFilePath(fileDetails.filePath)
            .setFileContent(fileDetails.fileContent)
            .setGitDiff(gitDiff)
            .setCursorPosition(request.caretOffset)
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
            .setFileName(editor.virtualFile?.name ?: "")
            .setFileContent(fileContent)
            .setGitDiff(GitUtil.getCurrentChanges(project) ?: "")
            .setCursorPosition(caretOffset)
            .putAllRecentlyViewedFiles(recentlyViewedPairs.toMap())
            .setPluginVersion(CodeGPTPlugin.getVersion())
            .build()
    }

    private fun createChannel(): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(resolveHost(), resolvePort())
            .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .keepAliveTime(2, TimeUnit.MINUTES)
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .idleTimeout(30, TimeUnit.MINUTES)
            .maxInboundMessageSize(32 * 1024 * 1024)

        if (java.lang.Boolean.getBoolean(PLAINTEXT_PROPERTY)) {
            builder.usePlaintext()
        } else {
            builder.useTransportSecurity()
            builder.sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(CertificateManager.getInstance().trustManager)
                    .build()
            )
        }

        return builder.build()
    }

    private fun resolveHost(): String {
        return System.getProperty(HOST_PROPERTY) ?: HOST
    }

    private fun resolvePort(): Int {
        return Integer.getInteger(PORT_PROPERTY, PORT)
    }

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
