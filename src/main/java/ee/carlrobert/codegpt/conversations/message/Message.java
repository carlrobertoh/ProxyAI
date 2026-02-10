package ee.carlrobert.codegpt.conversations.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ee.carlrobert.llm.client.openai.completion.response.ToolCall;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private String personaName;
  private List<ToolCall> toolCalls;
  private Map<String, String> toolCallResults;

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

  public @Nullable String getPersonaName() {
    return personaName;
  }

  public void setPersonaName(String personaName) {
    this.personaName = personaName;
  }

  @JsonProperty("tool_calls")
  public @Nullable List<ToolCall> getToolCalls() {
    return toolCalls;
  }

  @JsonProperty("tool_calls")
  public void setToolCalls(@Nullable List<ToolCall> toolCalls) {
    this.toolCalls = toolCalls;
  }

  public void addToolCall(ToolCall toolCall) {
    if (toolCalls == null) {
      toolCalls = new java.util.ArrayList<>();
    }
    toolCalls.add(toolCall);
  }

  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }

  @JsonProperty("tool_call_results")
  public @Nullable Map<String, String> getToolCallResults() {
    return toolCallResults;
  }

  @JsonProperty("tool_call_results")
  public void setToolCallResults(@Nullable Map<String, String> toolCallResults) {
    this.toolCallResults = toolCallResults;
  }

  public void addToolCallResult(String toolCallId, String executionOutput) {
    if (toolCallResults == null) {
      toolCallResults = new HashMap<>();
    }
    toolCallResults.put(toolCallId, executionOutput);
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
