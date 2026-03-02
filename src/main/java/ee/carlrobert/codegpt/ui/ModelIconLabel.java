package ee.carlrobert.codegpt.ui;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.settings.models.ModelSettings;
import ee.carlrobert.codegpt.settings.service.ServiceType;
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
      return ModelSettings.getInstance().getModelDisplayName(ServiceType.OPENAI, modelCode);
    } catch (Exception e) {
      return modelCode;
    }
  }
}
