package ee.carlrobert.codegpt.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel;
import java.util.NoSuchElementException;
import javax.swing.SwingConstants;

public class ModelIconLabel extends JBLabel {

  public ModelIconLabel(String clientCode, String modelCode) {
    if ("chat.completion".equals(clientCode)) {
      setIcon(Icons.OpenAI);
    }
    if ("anthropic.chat.completion".equals(clientCode)) {
      setIcon(Icons.Anthropic);
    }
    if ("llama.chat.completion".equals(clientCode)) {
      setIcon(Icons.Llama);
    }
    if ("google.chat.completion".equals(clientCode)) {
      setIcon(Icons.Google);
    }
    setText(formatModelName(modelCode));
    setFont(JBFont.small());
    setHorizontalAlignment(SwingConstants.LEADING);
  }

  private String formatModelName(String modelCode) {
    try {
      return OpenAIChatCompletionModel.findByCode(modelCode).getDescription();
    } catch (NoSuchElementException e) {
      return modelCode;
    }
  }
}
