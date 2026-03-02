package testsupport.http

data class ResponseEntity(
    val body: String,
    val statusCode: Int = 200,
    val contentType: String = "application/json",
)
