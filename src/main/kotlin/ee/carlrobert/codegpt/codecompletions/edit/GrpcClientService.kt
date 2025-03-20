package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.notification.NotificationAction.createSimpleExpiring
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.UUID
import ee.carlrobert.codegpt.credentials.CredentialsStore
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.telemetry.core.configuration.TelemetryConfiguration
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.service.AcceptEditRequest
import ee.carlrobert.service.NextEditRequest
import ee.carlrobert.service.NextEditResponse
import ee.carlrobert.service.NextEditServiceImplGrpc
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@Service(Service.Level.PROJECT)
class GrpcClientService(private val project: Project) : Disposable {

    private var channel: ManagedChannel? = null
    private var stub: NextEditServiceImplGrpc.NextEditServiceImplStub? = null
    private var prevObserver: NextEditStreamObserver? = null

    companion object {
        private const val HOST = "grpc.tryproxy.io"
        private const val PORT = 9090
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        private val logger = thisLogger()
    }

    @Synchronized
    private fun ensureConnection() {
        if (channel == null || channel?.isShutdown == true) {
            try {
                channel = NettyChannelBuilder.forAddress(HOST, PORT).build()
                stub = NextEditServiceImplGrpc.newStub(channel)
                    .withCallCredentials(
                        ApiKeyCredentials(CredentialsStore.getCredential(CodeGptApiKey) ?: "")
                    )

                logger.info("gRPC connection established")
            } catch (e: Exception) {
                logger.error("Failed to establish gRPC connection", e)
                throw e
            }
        }
    }

    fun getNextEdit(editor: Editor, isManuallyOpened: Boolean = false) {
        ensureConnection()
        prevObserver?.onCompleted()

        val request = NextEditRequest.newBuilder()
            .setFileName((editor as EditorEx).virtualFile.name)
            .setFileContent(editor.document.text)
            .setGitDiff(GitUtil.getCurrentChanges(project) ?: "")
            .setCursorPosition(runReadAction { editor.caretModel.offset })
            .setEnableTelemetry(TelemetryConfiguration.getInstance().isCompletionTelemetryEnabled)
            .build()
        prevObserver = NextEditStreamObserver(editor, isManuallyOpened) {
            dispose()
        }

        stub?.nextEdit(request, prevObserver)
    }

    class NextEditStreamObserver(
        private val editor: Editor,
        private val isManuallyOpened: Boolean,
        private val onDispose: () -> Unit
    ) : StreamObserver<NextEditResponse> {
        override fun onNext(response: NextEditResponse) {
            runInEdt {
                if (LookupManager.getActiveLookup(editor) == null) {
                    CodeSuggestionDiffViewer.displayInlineDiff(editor, response, isManuallyOpened)
                }
            }
        }

        override fun onError(ex: Throwable) {
            if (ex is CancellationException ||
                (ex is StatusRuntimeException && ex.status.code == Status.Code.CANCELLED)
            ) {
                onCompleted()
                return
            }

            try {
                if (ex is StatusRuntimeException) {
                    OverlayUtil.showNotification(
                        ex.status.description ?: ex.localizedMessage,
                        NotificationType.ERROR,
                        createSimpleExpiring("Disable multi-line edits") {
                            service<CodeGPTServiceSettings>().state.nextEditsEnabled =
                                false
                        })
                } else {
                    logger.error("Something went wrong", ex)
                }
            } finally {
                onCompleted()
                onDispose()
            }
        }

        override fun onCompleted() {
        }
    }

    fun acceptEdit(responseId: UUID, acceptedEdit: String) {
        NextEditServiceImplGrpc
            .newBlockingStub(channel)
            .acceptEdit(
                AcceptEditRequest.newBuilder()
                    .setResponseId(responseId.toString())
                    .setAcceptedEdit(acceptedEdit)
                    .build()
            )
    }

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
                    channel = null
                }
            }
        }
    }
}

internal class ApiKeyCredentials(private val apiKey: String) : CallCredentials() {

    companion object {
        private val API_KEY_HEADER: Metadata.Key<String> =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun applyRequestMetadata(
        requestInfo: RequestInfo?,
        executor: Executor,
        metadataApplier: MetadataApplier
    ) {
        executor.execute {
            try {
                val headers = Metadata()
                headers.put(API_KEY_HEADER, apiKey)
                metadataApplier.apply(headers)
            } catch (e: Throwable) {
                metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e))
            }
        }
    }

    override fun thisUsesUnstableApi() {
    }
}