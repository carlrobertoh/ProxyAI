package ee.carlrobert.codegpt.conversations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversationAttachedFile {

  private String path;
  private boolean selected;

  public ConversationAttachedFile() {
  }

  public ConversationAttachedFile(String path, boolean selected) {
    this.path = path;
    this.selected = selected;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public boolean isSelected() {
    return selected;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ConversationAttachedFile other)) {
      return false;
    }
    return selected == other.selected && Objects.equals(path, other.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, selected);
  }
}
