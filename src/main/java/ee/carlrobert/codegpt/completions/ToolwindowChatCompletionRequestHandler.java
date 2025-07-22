package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.service.ModelSelectionService;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import okhttp3.sse.EventSource;

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
      var featureType = callParameters.getFeatureType();
      var serviceType =
          ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT);
      var request = CompletionRequestFactory
          .getFactoryForFeature(featureType)
          .createChatRequest(callParameters);
      return CompletionRequestService.getInstance().getChatCompletionAsync(
          request,
          new ChatCompletionEventListener(
              project,
              callParameters,
              completionResponseEventListener),
          serviceType);
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
    var service = ModelSelectionService.getInstance()
        .getServiceForFeature(FeatureType.CHAT);
    TelemetryAction.COMPLETION.createActionMessage()
        .property("conversationId", callParameters.getConversation().getId().toString())
        .property("service", service.getCode().toLowerCase())
        .send();
  }
}
