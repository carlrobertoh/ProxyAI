package ee.carlrobert.codegpt.conversations.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
  private List<UUID> conversationsHistoryIds;
  private String imageFilePath;
  private boolean webSearchIncluded;
  private DocumentationDetails documentationDetails;
  private String personaName;

  public Message() {
    this.id = UUID.randomUUID();
  }

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

  public @Nullable List<String> getReferencedFilePaths() {
    return referencedFilePaths;
  }

  public void setReferencedFilePaths(List<String> referencedFilePaths) {
    this.referencedFilePaths = referencedFilePaths;
  }

  public List<UUID> getConversationsHistoryIds() {
    return conversationsHistoryIds;
  }

  public void setConversationsHistoryIds(List<UUID> conversationsHistoryIds) {
    this.conversationsHistoryIds = conversationsHistoryIds;
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

  public @Nullable DocumentationDetails getDocumentationDetails() {
    return documentationDetails;
  }

  public void setDocumentationDetails(DocumentationDetails documentationDetails) {
    this.documentationDetails = documentationDetails;
  }

  public @Nullable String getPersonaName() {
    return personaName;
  }

  public void setPersonaName(String personaName) {
    this.personaName = personaName;
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
