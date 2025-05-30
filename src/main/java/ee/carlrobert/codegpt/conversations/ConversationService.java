package ee.carlrobert.codegpt.conversations;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import ee.carlrobert.codegpt.completions.ChatCompletionParameters;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings;
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings;
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

@Service
public final class ConversationService {

  private final ConversationsState conversationState = ConversationsState.getInstance();

  private ConversationService() {
  }

  public static ConversationService getInstance() {
    return ApplicationManager.getApplication().getService(ConversationService.class);
  }

  public List<Conversation> getSortedConversations() {
    return conversationState.getConversationsMapping()
        .values()
        .stream()
        .flatMap(List::stream)
        .sorted(Comparator.comparing(Conversation::getUpdatedOn).reversed())
        .toList();
  }

  public Conversation createConversation(String clientCode) {
    var conversation = new Conversation();
    conversation.setId(UUID.randomUUID());
    conversation.setClientCode(clientCode);
    conversation.setCreatedOn(LocalDateTime.now());
    conversation.setUpdatedOn(LocalDateTime.now());
    conversation.setModel(getModelForSelectedService(GeneralSettings.getSelectedService()));
    return conversation;
  }

  public void addConversation(Conversation conversation) {
    var conversationsMapping = conversationState.getConversationsMapping();
    var conversations = conversationsMapping.get(conversation.getClientCode());
    if (conversations == null) {
      conversations = new ArrayList<>();
    }
    conversations.add(conversation);
    conversationsMapping.put(conversation.getClientCode(), conversations);
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
    var iterator = getIterator(conversation.getClientCode());
    while (iterator.hasNext()) {
      var next = iterator.next();
      next.setMessages(
          next.getMessages().stream().map(item -> {
            if (item.getId() == message.getId()) {
              return message;
            }
            return item;
          }).toList());
      if (next.getId().equals(conversation.getId())) {
        iterator.set(conversation);
      }
    }
  }

  public void saveConversation(Conversation conversation) {
    conversation.setUpdatedOn(LocalDateTime.now());
    var iterator = getIterator(conversation.getClientCode());
    while (iterator.hasNext()) {
      var next = iterator.next();
      if (next.getId().equals(conversation.getId())) {
        iterator.set(conversation);
      }
    }
    conversationState.setCurrentConversation(conversation);
  }

  public Conversation startConversation() {
    var completionCode = GeneralSettings.getSelectedService().getCompletionCode();
    var conversation = createConversation(completionCode);
    conversationState.setCurrentConversation(conversation);
    addConversation(conversation);
    return conversation;
  }

  public void clearAll() {
    conversationState.getConversationsMapping().clear();
    conversationState.setCurrentConversation(null);
  }

  public void deleteConversation(Conversation conversation) {
    var iterator = getIterator(conversation.getClientCode());
    while (iterator.hasNext()) {
      var next = iterator.next();
      if (next.getId().equals(conversation.getId())) {
        iterator.remove();
        break;
      }
    }
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

  private ListIterator<Conversation> getIterator(String clientCode) {
    return conversationState.getConversationsMapping()
        .get(clientCode)
        .listIterator();
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

  public String getModelForSelectedService(ServiceType serviceType) {
    var application = ApplicationManager.getApplication();
    return switch (serviceType) {
      case CODEGPT -> application.getService(CodeGPTServiceSettings.class)
          .getState()
          .getChatCompletionSettings()
          .getModel();
      case OPENAI -> OpenAISettings.getCurrentState().getModel();
      case CUSTOM_OPENAI -> "CustomService";
      case ANTHROPIC -> AnthropicSettings.getCurrentState().getModel();
      case LLAMA_CPP -> {
        var llamaSettings = LlamaSettings.getCurrentState();
        yield llamaSettings.isUseCustomModel()
            ? llamaSettings.getCustomLlamaModelPath()
            : llamaSettings.getHuggingFaceModel().getCode();
      }
      case OLLAMA -> application.getService(OllamaSettings.class)
          .getState()
          .getModel();
      case GOOGLE -> application.getService(GoogleSettings.class)
          .getState()
          .getModel();
    };
  }
}
