package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import okhttp3.sse.EventSource;

import java.util.UUID;

public class ToolwindowChatCompletionRequestHandler {

  private final Project project;
  private final CompletionResponseEventListener completionResponseEventListener;
  private EventSource eventSource;

  public ToolwindowChatCompletionRequestHandler(
      Project project,
      CompletionResponseEventListener completionResponseEventListener) {
    this.project = project;
    this.completionResponseEventListener = completionResponseEventListener;
  }

  public void call(ChatCompletionParameters callParameters) {
    try {
      callParameters.setRequestId(UUID.randomUUID());
      eventSource = startCall(callParameters);
    } catch (TotalUsageExceededException e) {
      completionResponseEventListener.handleTokensExceeded(
          callParameters.getConversation(),
          callParameters.getMessage());
    } finally {
      sendInfo(callParameters);
    }
  }

  public void cancel() {
    if (eventSource != null) {
      eventSource.cancel();
    }
  }

  private EventSource startCall(ChatCompletionParameters callParameters) {
    try {
      CompletionProgressNotifier.Companion.update(project, true);
      var request = CompletionRequestFactory
          .getFactory(GeneralSettings.getSelectedService())
          .createChatRequest(callParameters);
      return CompletionRequestService.getInstance().getChatCompletionAsync(
          request,
          new ChatCompletionEventListener(
              project,
              callParameters,
              completionResponseEventListener));
    } catch (Throwable ex) {
      handleCallException(ex);
      throw ex;
    }
  }

  private void handleCallException(Throwable ex) {
    var errorMessage = "Something went wrong";
    if (ex instanceof TotalUsageExceededException) {
      errorMessage =
          "The length of the context exceeds the maximum limit that the model can handle. "
              + "Try reducing the input message or maximum completion token size.";
    }
    completionResponseEventListener.handleError(new ErrorDetails(errorMessage), ex);
  }

  private void sendInfo(ChatCompletionParameters callParameters) {
    TelemetryAction.COMPLETION.createActionMessage()
        .property("conversationId", callParameters.getConversation().getId().toString())
        .property("model", callParameters.getConversation().getModel())
        .property("service", GeneralSettings.getSelectedService().getCode().toLowerCase())
        .send();
  }
}
