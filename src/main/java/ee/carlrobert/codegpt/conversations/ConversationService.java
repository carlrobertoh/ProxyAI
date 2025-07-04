package ee.carlrobert.codegpt.conversations;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.completions.ChatCompletionParameters;
import ee.carlrobert.codegpt.conversations.message.Message;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

@Service
public final class ConversationService {

  private static final Logger LOG = Logger.getInstance(ConversationService.class);

  private final ConversationsState conversationState = ConversationsState.getInstance();

  private ConversationService() {
  }

  public static ConversationService getInstance() {
    return ApplicationManager.getApplication().getService(ConversationService.class);
  }

  public List<Conversation> getSortedConversations() {
    return conversationState.conversations
        .stream()
        .sorted(Comparator.comparing(Conversation::getUpdatedOn).reversed())
        .toList();
  }

  public Conversation createConversation() {
    var conversation = new Conversation();
    conversation.setId(UUID.randomUUID());
    conversation.setCreatedOn(LocalDateTime.now());
    conversation.setUpdatedOn(LocalDateTime.now());
    return conversation;
  }

  public void addConversation(Conversation conversation) {
    conversationState.conversations.add(conversation);
  }

  public void saveMessage(String response, ChatCompletionParameters callParameters) {
    var conversation = callParameters.getConversation();
    var message = callParameters.getMessage();
    var conversationMessages = conversation.getMessages();
    if (callParameters.getRetry() && !conversationMessages.isEmpty()) {
      var messageToBeSaved = conversationMessages.stream()
          .filter(item -> item.getId().equals(message.getId()))
          .findFirst().orElseThrow();
      messageToBeSaved.setResponse(response);
      saveConversation(conversation);
      return;
    }

    message.setResponse(response);
    conversation.addMessage(message);
    saveConversation(conversation);
  }

  public void saveMessage(@NotNull Conversation conversation, @NotNull Message message) {
    conversation.setUpdatedOn(LocalDateTime.now());
    conversation.addMessage(message);
  }

  public void saveConversation(Conversation conversation) {
    conversation.setUpdatedOn(LocalDateTime.now());
    conversationState.setCurrentConversation(conversation);
  }

  public Conversation startConversation() {
    var conversation = createConversation();
    conversationState.setCurrentConversation(conversation);
    addConversation(conversation);
    return conversation;
  }

  public void clearAll() {
    conversationState.conversations.clear();
    conversationState.setCurrentConversation(null);
  }

  public void deleteConversation(Conversation conversation) {
    conversationState.conversations.removeIf(it -> it.getId() == conversation.getId());
  }

  public void deleteSelectedConversation() {
    var nextConversation = getPreviousConversation();
    if (nextConversation.isEmpty()) {
      nextConversation = getNextConversation();
    }

    var currentConversation = ConversationsState.getCurrentConversation();
    if (currentConversation != null) {
      deleteConversation(currentConversation);
      nextConversation.ifPresent(conversationState::setCurrentConversation);
    } else {
      throw new RuntimeException("Tried to delete a conversation that hasn't been set");
    }
  }

  public void discardTokenLimits(Conversation conversation) {
    conversation.discardTokenLimits();
    saveConversation(conversation);
  }

  public Optional<Conversation> getPreviousConversation() {
    return tryGetNextOrPreviousConversation(true);
  }

  public Optional<Conversation> getNextConversation() {
    return tryGetNextOrPreviousConversation(false);
  }

  private Optional<Conversation> tryGetNextOrPreviousConversation(boolean isPrevious) {
    var currentConversation = ConversationsState.getCurrentConversation();
    if (currentConversation != null) {
      var sortedConversations = getSortedConversations();
      for (int i = 0; i < sortedConversations.size(); i++) {
        var conversation = sortedConversations.get(i);
        if (conversation != null && conversation.getId().equals(currentConversation.getId())) {
          // higher index indicates older conversation
          var previousIndex = isPrevious ? i + 1 : i - 1;
          if (isPrevious ? previousIndex < sortedConversations.size() : previousIndex != -1) {
            return Optional.of(sortedConversations.get(previousIndex));
          }
        }
      }
    }
    return Optional.empty();
  }
}
