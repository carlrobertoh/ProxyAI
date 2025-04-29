package ee.carlrobert.codegpt.settings.persona

import com.intellij.openapi.components.*
import ee.carlrobert.codegpt.settings.prompts.PersonasState

@Deprecated("Use PromptsSettings instead")
@Service
@State(
    name = "CodeGPT_PersonaSettings",
    storages = [Storage("CodeGPT_PersonaSettings.xml")]
)
class PersonaSettings :
    SimplePersistentStateComponent<PersonaSettingsState>(PersonaSettingsState())

class PersonaSettingsState : BaseState() {
    var userCreatedPersonas by list<PersonaDetailsState>()
}

class PersonaDetailsState : BaseState() {
    var id by property(PersonasState.DEFAULT_PERSONA.id)
    var name by string(PersonasState.DEFAULT_PERSONA.name)
    var instructions by string(PersonasState.DEFAULT_PERSONA.instructions)
}
