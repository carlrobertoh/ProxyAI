package ee.carlrobert.codegpt.conversations.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.util.BaseConverter;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ConversationListConverter extends BaseConverter<List<Conversation>> {

  public ConversationListConverter() {
    super(new TypeReference<>() {});
  }
  
  @Override
  public List<Conversation> fromString(@NotNull String value) {
    List<Conversation> result = super.fromString(value);
    return result != null ? result : new ArrayList<>();
  }
}