package ee.carlrobert.codegpt.conversations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ee.carlrobert.codegpt.conversations.message.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Conversation {

  private UUID id;
  private String title;
  private List<Message> messages;
  private LocalDateTime createdOn;
  private LocalDateTime updatedOn;
  private boolean discardTokenLimit;

  public Conversation() {
    this.messages = new ArrayList<>();
    this.id = UUID.randomUUID();
    this.createdOn = LocalDateTime.now();
    this.updatedOn = LocalDateTime.now();
    this.discardTokenLimit = false;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public List<Message> getMessages() {
    return messages;
  }

  public void setMessages(List<Message> messages) {
    this.messages = new ArrayList<>(messages);
  }

  public void addMessage(Message message) {
    messages.add(message);
  }

  public LocalDateTime getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(LocalDateTime createdOn) {
    this.createdOn = createdOn;
  }

  public LocalDateTime getUpdatedOn() {
    return updatedOn;
  }

  public void setUpdatedOn(LocalDateTime updatedOn) {
    this.updatedOn = updatedOn;
  }

  public void discardTokenLimits() {
    this.discardTokenLimit = true;
  }

  public boolean isDiscardTokenLimit() {
    return discardTokenLimit;
  }

  public void removeMessage(UUID messageId) {
    messages = new ArrayList<>(messages.stream()
        .filter(message -> !message.getId().equals(messageId))
        .toList());
  }
}
