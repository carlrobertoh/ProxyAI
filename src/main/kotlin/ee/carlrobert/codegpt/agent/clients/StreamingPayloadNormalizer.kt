package ee.carlrobert.codegpt.agent.clients

internal fun normalizeSsePayload(rawData: String): String? {
    val normalized = rawData
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (normalized.isEmpty()) {
        return null
    }

    val dataLines = normalized.lineSequence()
        .mapNotNull { line ->
            val markerIndex = line.indexOf("data:")
            if (markerIndex >= 0) {
                line.substring(markerIndex + "data:".length).trimStart()
            } else {
                null
            }
        }
        .filter { it.isNotEmpty() }
        .toList()

    val payload = if (dataLines.isNotEmpty()) {
        dataLines.joinToString("\n")
    } else {
        normalized
    }

    return payload.takeUnless { it.isBlank() || it == "[DONE]" }
}
