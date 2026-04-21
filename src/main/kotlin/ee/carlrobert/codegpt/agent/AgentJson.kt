package ee.carlrobert.codegpt.agent

import kotlinx.serialization.json.Json

internal val agentJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}
