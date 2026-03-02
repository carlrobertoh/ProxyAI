package ee.carlrobert.codegpt.completions;

import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.events.CodeGPTEvent;
import javax.swing.JPanel;

public interface CompletionResponseEventListener {

  default void handleMessage(String message) {
  }

  default void handleError(ChatError error, Throwable ex) {
  }

  default void handleTokensExceeded(Conversation conversation, Message message) {
  }

  default void handleCompleted(String fullMessage) {
  }

  default void handleCompleted(String fullMessage, ChatCompletionParameters callParameters) {
  }

  default void handleCodeGPTEvent(CodeGPTEvent event) {
  }

  default void handleRequestOpen() {
  }
}
