package ee.carlrobert.codegpt.settings.service.custom.form

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class CustomSettingsFileProvider {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(Jdk8Module())
        .registerModule(JavaTimeModule())
        .registerModule(KotlinModule.Builder().build())
        .apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }

    suspend fun writeSettings(path: String, data: List<CustomServiceSettingsData>) {
        withContext(Dispatchers.IO) {
            // Cleanup api keys from file
            val dataWithoutApiKeys = data.map { it.copy(apiKey = "") }
            val serializedFiles = objectMapper.writeValueAsString(dataWithoutApiKeys)
            FileWriter(path).use {
                it.write(serializedFiles)
            }
        }
    }

    fun readFromFile(path: String): List<CustomServiceSettingsData> =
        objectMapper.readValue<List<CustomServiceSettingsData>>(File(path))
}