package ee.carlrobert.codegpt.settings.service.custom.form

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import ee.carlrobert.codegpt.settings.service.custom.form.model.CustomServiceSettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileWriter

class CustomSettingsFileProvider {

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(Jdk8Module())
        .registerModule(JavaTimeModule())

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
}