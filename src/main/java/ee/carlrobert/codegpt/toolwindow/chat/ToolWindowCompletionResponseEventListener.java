package ee.carlrobert.codegpt.toolwindow.chat;

import static com.intellij.openapi.ui.Messages.OK;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.EncodingManager;
import ee.carlrobert.codegpt.completions.CallParameters;
import ee.carlrobert.codegpt.events.CodeGPTEvent;
import ee.carlrobert.codegpt.completions.CompletionResponseEventListener;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.ui.ChatMessageResponseBody;
import ee.carlrobert.codegpt.toolwindow.chat.ui.ResponsePanel;
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.TotalTokensPanel;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel;
import ee.carlrobert.llm.client.openai.completion.ErrorDetails;
import javax.swing.SwingUtilities;

abstract class ToolWindowCompletionResponseEventListener implements
    CompletionResponseEventListener {

  private static final Logger LOG = Logger.getInstance(
      ToolWindowCompletionResponseEventListener.class);

  private final StringBuilder messageBuilder = new StringBuilder();
  private final EncodingManager encodingManager;
  private final ConversationService conversationService;
  private final ResponsePanel responsePanel;
  private final ChatMessageResponseBody responseContainer;
  private final TotalTokensPanel totalTokensPanel;
  private final UserInputPanel textArea;

  private volatile boolean completed;

  public ToolWindowCompletionResponseEventListener(
      ConversationService conversationService,
      ResponsePanel responsePanel,
      TotalTokensPanel totalTokensPanel,
      UserInputPanel textArea) {
    this.encodingManager = EncodingManager.getInstance();
    this.conversationService = conversationService;
    this.responsePanel = responsePanel;
    this.responseContainer = (ChatMessageResponseBody) responsePanel.getContent();
    this.totalTokensPanel = totalTokensPanel;
    this.textArea = textArea;
  }

  public abstract void handleTokensExceededPolicyAccepted();

  @Override
  public void handleMessage(String partialMessage) {
    try {
      ApplicationManager.getApplication()
          .invokeLater(() -> {
            responseContainer.update(partialMessage);
            messageBuilder.append(partialMessage);

            if (!completed) {
              var ongoingTokens = encodingManager.countTokens(messageBuilder.toString());
              totalTokensPanel.update(
                  totalTokensPanel.getTokenDetails().getTotal() + ongoingTokens);
            }
          });
    } catch (Exception e) {
      responseContainer.displayError("Something went wrong.");
      throw new RuntimeException("Error while updating the content", e);
    }
  }

  @Override
  public void handleError(ErrorDetails error, Throwable ex) {
    SwingUtilities.invokeLater(() -> {
      try {
        if ("insufficient_quota".equals(error.getCode())) {
          responseContainer.displayQuotaExceeded();
        } else {
          responseContainer.displayError(error.getMessage());
        }
      } finally {
        LOG.error(error.getMessage(), ex);
        responsePanel.enableActions();
        stopStreaming(responseContainer);
      }
    });
  }

  @Override
  public void handleTokensExceeded(Conversation conversation, Message message) {
    SwingUtilities.invokeLater(() -> {
      var answer = OverlayUtil.showTokenLimitExceededDialog();
      if (answer == OK) {
        TelemetryAction.IDE_ACTION.createActionMessage()
            .property("action", "DISCARD_TOKEN_LIMIT")
            .property("model", conversation.getModel())
            .send();

        conversationService.discardTokenLimits(conversation);
        handleTokensExceededPolicyAccepted();
      } else {
        stopStreaming(responseContainer);
      }
    });
  }

  @Override
  public void handleCompleted(String fullMessage, CallParameters callParameters) {
    conversationService.saveMessage(fullMessage, callParameters);

    SwingUtilities.invokeLater(() -> {
      try {
        responsePanel.enableActions();
        totalTokensPanel.updateUserPromptTokens(textArea.getText());
        totalTokensPanel.updateConversationTokens(callParameters.getConversation());
      } finally {
        stopStreaming(responseContainer);
      }
    });
  }

  @Override
  public void handleCodeGPTEvent(CodeGPTEvent event) {
    responseContainer.displayWebSearchItem(event.getEvent().getDetails());
  }

  private void stopStreaming(ChatMessageResponseBody responseContainer) {
    completed = true;
    textArea.setSubmitEnabled(true);
    responseContainer.hideCaret();
  }
}
