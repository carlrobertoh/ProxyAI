package testsupport.http.exchange

import testsupport.http.RequestEntity
import testsupport.http.ResponseEntity

fun interface BasicHttpExchange {
    fun exchange(request: RequestEntity): ResponseEntity
}

fun interface StreamHttpExchange {
    fun exchange(request: RequestEntity): List<String>
}

fun interface NdJsonStreamHttpExchange {
    fun exchange(request: RequestEntity): List<String>
}
