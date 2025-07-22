package ee.carlrobert.codegpt

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.migration.LegacySettingsMigration
import ee.carlrobert.codegpt.settings.models.ModelSettings

class LegacyMigrationActivity : ProjectActivity {

    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        try {
            val generalState = GeneralSettings.getCurrentState()
            val selectedService = generalState.selectedService
            
            if (selectedService == null) {
                return
            }
            
            val migratedState = LegacySettingsMigration.migrateIfNeeded()
            if (migratedState != null) {
                service<ModelSettings>().loadState(migratedState)
            }
            
            generalState.selectedService = null
            
        } catch (exception: Exception) {
            logger.error("Failed to execute legacy migration", exception)
            GeneralSettings.getCurrentState().selectedService = null
        }
    }
}