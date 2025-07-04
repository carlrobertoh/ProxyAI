package ee.carlrobert.codegpt.util

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.xmlb.Converter

abstract class BaseConverter<T : Any> protected constructor(private val typeReference: TypeReference<T>) : Converter<T>() {
  private val objectMapper: ObjectMapper = ObjectMapper()
    .registerModule(Jdk8Module())
    .registerModule(JavaTimeModule())

  private val logger = thisLogger()

  override fun fromString(value: String): T? {
    try {
      return objectMapper.readValue(value, typeReference)
    } catch (e: JsonProcessingException) {
      logger.debug("Unable to deserialize data", e)
      return null
    }
  }

  override fun toString(value: T): String? {
    try {
      return objectMapper.writeValueAsString(value)
    } catch (e: JsonProcessingException) {
      logger.debug("Unable to serialize data", e)
      return null
    }
  }
}
