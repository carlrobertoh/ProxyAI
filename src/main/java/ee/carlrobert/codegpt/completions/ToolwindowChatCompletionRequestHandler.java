package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier;
import ee.carlrobert.codegpt.mcp.McpToolCallEventListener;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.service.ModelSelectionService;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowTabPanel;
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel;
import ee.carlrobert.codegpt.toolwindow.ui.mcp.McpApprovalPanel;
import ee.carlrobert.codegpt.toolwindow.ui.mcp.ToolGroupPanel;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import ee.carlrobert.llm.completion.CompletionEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.sse.EventSource;

public class ToolwindowChatCompletionRequestHandler {

  private final Project project;
  private final CompletionResponseEventListener completionResponseEventListener;
  private final ChatToolWindowTabPanel tabPanel;
  private final List<EventSource> activeEventSources = new ArrayList<>();
  private final AtomicBoolean isCancelled = new AtomicBoolean(false);
  private ResponseMessagePanel responseMessagePanel;

  public ToolwindowChatCompletionRequestHandler(
      Project project,
      CompletionResponseEventListener completionResponseEventListener,
      ChatToolWindowTabPanel tabPanel) {
    this.project = project;
    this.completionResponseEventListener = completionResponseEventListener;
    this.tabPanel = tabPanel;
  }

  public void setResponseMessagePanel(ResponseMessagePanel panel) {
    this.responseMessagePanel = panel;
  }

  public void call(ChatCompletionParameters callParameters) {
    isCancelled.set(false);
    synchronized (activeEventSources) {
      activeEventSources.clear();
    }

    try {
      EventSource eventSource = startCall(callParameters);
      if (eventSource != null) {
        synchronized (activeEventSources) {
          activeEventSources.add(eventSource);
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

    synchronized (activeEventSources) {
      for (EventSource source : activeEventSources) {
        if (source != null) {
          source.cancel();
        }
      }
      activeEventSources.clear();
    }
  }

  public boolean isCancelled() {
    return isCancelled.get();
  }

  public void addEventSource(EventSource eventSource) {
    if (eventSource != null && !isCancelled.get()) {
      synchronized (activeEventSources) {
        activeEventSources.add(eventSource);
      }
    }
  }

  private CompletionEventListener<String> getEventListener(
      ChatCompletionParameters callParameters) {
    if (callParameters.getMcpTools() != null && !callParameters.getMcpTools().isEmpty()) {
      return new McpToolCallEventListener(
          project,
          callParameters,
          completionResponseEventListener,
          panel -> {
            if (panel instanceof McpApprovalPanel || panel instanceof ToolGroupPanel) {
              tabPanel.addToolCallApprovalPanel(panel);
            } else {
              tabPanel.addToolCallStatusPanel(panel);
            }
            return kotlin.Unit.INSTANCE;
          },
          this);
    }

    return new ChatCompletionEventListener(
        project,
        callParameters,
        completionResponseEventListener);
  }

  private EventSource startCall(ChatCompletionParameters callParameters) {
    try {
      CompletionProgressNotifier.Companion.update(project, true);
      var serviceType =
          ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT);
      var eventListener = getEventListener(callParameters);
      var request = CompletionRequestFactory.getFactory(serviceType)
          .createChatRequest(callParameters);

      try {
        return CompletionRequestService.getInstance()
            .getChatCompletionAsync(request, eventListener);
      } catch (Exception e) {
        eventListener.onError(new ErrorDetails("Failed to start request: " + e.getMessage()), e);
        return null;
      }
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
