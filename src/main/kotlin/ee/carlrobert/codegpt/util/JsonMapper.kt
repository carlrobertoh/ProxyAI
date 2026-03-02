package ee.carlrobert.codegpt.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JsonMapper {
    val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
}
