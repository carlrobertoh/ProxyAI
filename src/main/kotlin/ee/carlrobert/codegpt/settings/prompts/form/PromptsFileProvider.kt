package ee.carlrobert.codegpt.settings.prompts.form

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings
import ee.carlrobert.codegpt.settings.prompts.PromptsSettingsState
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class PromptsFileProvider {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(Jdk8Module())
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }

    suspend fun writePrompts(path: String, data: PromptsSettingsState) = withContext(Dispatchers.IO) {
        val serializedFiles = objectMapper.writeValueAsString(data)
        FileWriter(path).use {
            it.write(serializedFiles)
        }
    }

    fun readFromFile(path: String): PromptsSettingsState =
        objectMapper.readValue<PromptsSettingsState>(File(path))
}