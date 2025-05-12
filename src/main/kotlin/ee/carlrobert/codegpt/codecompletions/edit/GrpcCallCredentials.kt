package ee.carlrobert.codegpt.codecompletions.edit

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.grpc.Status
import java.util.concurrent.Executor

class GrpcCallCredentials(private val apiKey: String) : CallCredentials() {

    companion object {
        private val API_KEY_HEADER = Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER)
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
}