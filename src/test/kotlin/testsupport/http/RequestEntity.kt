package testsupport.http

import java.net.URI

data class RequestEntity(
    val method: String,
    val uri: URI,
    val body: Map<String, Any?>,
)
