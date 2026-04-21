package ee.carlrobert.codegpt.agent

import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer

internal val koogJsonSerializer: JSONSerializer = KotlinxSerializer(agentJson)
