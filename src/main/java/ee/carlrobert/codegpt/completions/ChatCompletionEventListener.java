package ee.carlrobert.codegpt.completions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier;
import ee.carlrobert.codegpt.events.CodeGPTEvent;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import ee.carlrobert.llm.completion.CompletionEventListener;
import okhttp3.sse.EventSource;

public class ChatCompletionEventListener implements CompletionEventListener<String> {

  private static final Logger LOG = Logger.getInstance(ChatCompletionEventListener.class);
  
  private final Project project;
  private final ChatCompletionParameters callParameters;
  private final CompletionResponseEventListener eventListener;
  private final StringBuilder messageBuilder = new StringBuilder();

  public ChatCompletionEventListener(
      Project project,
      ChatCompletionParameters callParameters,
      CompletionResponseEventListener eventListener) {
    this.project = project;
    this.callParameters = callParameters;
    this.eventListener = eventListener;
  }

  @Override
  public void onOpen() {
    eventListener.handleRequestOpen();
  }

  @Override
  public void onEvent(String data) {
    try {
      var event = new ObjectMapper().readValue(data, CodeGPTEvent.class);
      eventListener.handleCodeGPTEvent(event);
    } catch (JsonProcessingException exception) {
      LOG.debug("Failed to parse CodeGPTEvent from data: " + data, exception);
    }
  }

  @Override
  public void onMessage(String message, EventSource eventSource) {
    messageBuilder.append(message);
    callParameters.getMessage().setResponse(messageBuilder.toString());
    eventListener.handleMessage(message);
  }

  @Override
  public void onComplete(StringBuilder messageBuilder) {
    handleCompleted(messageBuilder);
  }

  @Override
  public void onCancelled(StringBuilder messageBuilder) {
    handleCompleted(messageBuilder);
  }

  @Override
  public void onError(ErrorDetails error, Throwable ex) {
    try {
      callParameters.getConversation().addMessage(callParameters.getMessage());
      eventListener.handleError(error, ex);
    } finally {
      sendError(error, ex);
    }
  }

  private void handleCompleted(StringBuilder messageBuilder) {
    CompletionProgressNotifier.Companion.update(project, false);
    eventListener.handleCompleted(messageBuilder.toString(), callParameters);
  }

  private void sendError(ErrorDetails error, Throwable ex) {
    var telemetryMessage = TelemetryAction.COMPLETION_ERROR.createActionMessage();
    if ("insufficient_quota".equals(error.getCode())) {
      telemetryMessage
          .property("type", "USER")
          .property("code", "INSUFFICIENT_QUOTA");
    } else {
      telemetryMessage
          .property("conversationId", callParameters.getConversation().getId().toString())
          .error(new RuntimeException(error.toString(), ex));
    }
    telemetryMessage.send();
  }
}
