package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.models.ModelSettings;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowTabPanel;
import ee.carlrobert.codegpt.toolwindow.agent.ui.SimpleAgentApprovalPanel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ToolwindowChatCompletionRequestHandler {

  private final Project project;
  private final CompletionResponseEventListener completionResponseEventListener;
  private final ChatToolWindowTabPanel tabPanel;
  private final List<CancellableRequest> activeRequests = new ArrayList<>();
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);

  public ToolwindowChatCompletionRequestHandler(
      Project project,
      CompletionResponseEventListener completionResponseEventListener,
      ChatToolWindowTabPanel tabPanel) {
    this.project = project;
    this.completionResponseEventListener = completionResponseEventListener;
    this.tabPanel = tabPanel;
  }

  public void call(ChatCompletionParameters callParameters) {
    isCancelled.set(false);
    synchronized (activeRequests) {
      activeRequests.clear();
    }

    try {
      CancellableRequest request = startCall(callParameters);
      if (request != null) {
        synchronized (activeRequests) {
          activeRequests.add(request);
        }
      }
    } catch (TotalUsageExceededException e) {
      completionResponseEventListener.handleTokensExceeded(
          callParameters.getConversation(),
          callParameters.getMessage());
    } finally {
      sendInfo(callParameters);
    }
  }

  public void cancel() {
    isCancelled.set(true);

    synchronized (activeRequests) {
      for (CancellableRequest request : activeRequests) {
        if (request != null) {
          request.cancel();
        }
      }
      activeRequests.clear();
    }
  }

  public boolean isCancelled() {
    return isCancelled.get();
  }

  private ChatStreamEventListener getEventListener(ChatCompletionParameters callParameters) {
    return new ChatCompletionEventListener(
        project,
        callParameters,
        completionResponseEventListener);
  }

  private CancellableRequest startCall(ChatCompletionParameters callParameters) {
    try {
      CompletionProgressNotifier.Companion.update(project, true);
      var serviceType =
          ModelSettings.getInstance().getServiceForFeature(FeatureType.CHAT);
      var modelSelection =
          ModelSettings.getInstance().getModelSelectionForFeature(FeatureType.CHAT);
      var factory = CompletionRequestFactory.getFactory(serviceType);
      var prompt = factory.createChatCompletionPrompt(callParameters);
      return CompletionRequestService.getChatCompletionAsync(
          serviceType,
          prompt,
          modelSelection,
          callParameters,
          getEventListener(callParameters),
          panel -> {
            if (panel instanceof SimpleAgentApprovalPanel) {
              tabPanel.addToolCallApprovalPanel(panel);
            } else {
              tabPanel.addToolCallStatusPanel(panel);
            }
            return kotlin.Unit.INSTANCE;
          });
    } catch (Throwable ex) {
      handleCallException(ex);
    }
    return null;
  }

  private void handleCallException(Throwable ex) {
    var errorMessage = "Something went wrong";
    if (ex instanceof TotalUsageExceededException) {
      errorMessage =
          "The length of the context exceeds the maximum limit that the model can handle. "
              + "Try reducing the input message or maximum completion token size.";
    }
    completionResponseEventListener.handleError(new ChatError(errorMessage), ex);
  }

  private void sendInfo(ChatCompletionParameters callParameters) {
    var service = ModelSettings.getInstance()
        .getServiceForFeature(FeatureType.CHAT);
    TelemetryAction.COMPLETION.createActionMessage()
        .property("conversationId", callParameters.getConversation().getId().toString())
        .property("service", service.getCode().toLowerCase())
        .send();
  }
}
