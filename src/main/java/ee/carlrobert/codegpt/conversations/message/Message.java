package ee.carlrobert.codegpt.conversations.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ee.carlrobert.codegpt.settings.persona.PersonaDetails;
import ee.carlrobert.codegpt.ui.DocumentationDetails;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

  private final UUID id;
  private String prompt;
  private String response;
  private List<String> referencedFilePaths;
  private @Nullable String imageFilePath;
  private boolean webSearchIncluded;
  private DocumentationDetails documentationDetails;
  private PersonaDetails personaDetails;

  public Message(String prompt, String response) {
    this(prompt);
    this.response = response;
  }

  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public Message(@JsonProperty("prompt") String prompt) {
    this.id = UUID.randomUUID();
    this.prompt = prompt;
  }

  public UUID getId() {
    return id;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public String getResponse() {
    return response;
  }

  public void setResponse(String response) {
    this.response = response;
  }

  public List<String> getReferencedFilePaths() {
    return referencedFilePaths;
  }

  public void setReferencedFilePaths(List<String> referencedFilePaths) {
    this.referencedFilePaths = referencedFilePaths;
  }

  public @Nullable String getImageFilePath() {
    return imageFilePath;
  }

  public void setImageFilePath(@Nullable String imageFilePath) {
    this.imageFilePath = imageFilePath;
  }

  public boolean isWebSearchIncluded() {
    return webSearchIncluded;
  }

  public void setWebSearchIncluded(boolean webSearchIncluded) {
    this.webSearchIncluded = webSearchIncluded;
  }

  public DocumentationDetails getDocumentationDetails() {
    return documentationDetails;
  }

  public void setDocumentationDetails(DocumentationDetails documentationDetails) {
    this.documentationDetails = documentationDetails;
  }

  public PersonaDetails getPersonaDetails() {
    return personaDetails;
  }

  public void setPersonaDetails(PersonaDetails personaDetails) {
    this.personaDetails = personaDetails;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof Message other)) {
      return false;
    }
    return Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, prompt);
  }
}
