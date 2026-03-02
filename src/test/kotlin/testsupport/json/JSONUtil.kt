package testsupport.json

import ee.carlrobert.codegpt.util.JsonMapper

object JSONUtil {

    fun e(key: String, value: Any?): Pair<String, Any?> = key to value

    fun jsonArray(vararg values: Any?): List<Any?> = values.toList()

    fun jsonMap(vararg entries: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*entries)

    fun jsonMap(key: String, value: Any?): Map<String, Any?> = linkedMapOf(key to value)

    fun jsonMapResponse(vararg entries: Pair<String, Any?>): String {
        return JsonMapper.mapper.writeValueAsString(jsonMap(*entries))
    }

    fun jsonMapResponse(key: String, value: Any?): String {
        return JsonMapper.mapper.writeValueAsString(jsonMap(key, value))
    }
}
