package ee.carlrobert.codegpt;

import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.AnimatedIcon;
import javax.swing.Icon;

public final class Icons {

  public static final Icon Default = IconLoader.getIcon("/icons/codegpt.svg", Icons.class);
  public static final Icon DefaultSmall =
      IconLoader.getIcon("/icons/codegpt-small.svg", Icons.class);
  public static final Icon CodeGPTModel =
      IconLoader.getIcon("/icons/codegpt-model.svg", Icons.class);
  public static final Icon CollapseAll =
      IconLoader.getIcon("/icons/collapseAll.svg", Icons.class);
  public static final Icon ExpandAll =
      IconLoader.getIcon("/icons/expandAll.svg", Icons.class);
  public static final Icon Anthropic = IconLoader.getIcon("/icons/anthropic.svg", Icons.class);
  public static final Icon DeepSeek = IconLoader.getIcon("/icons/deepseek.png", Icons.class);
  public static final Icon Qwen = IconLoader.getIcon("/icons/qwen.png", Icons.class);
  public static final Icon Google = IconLoader.getIcon("/icons/google.svg", Icons.class);
  public static final Icon Llama = IconLoader.getIcon("/icons/llama.svg", Icons.class);
  public static final Icon OpenAI = IconLoader.getIcon("/icons/openai.svg", Icons.class);
  public static final Icon MCP = IconLoader.getIcon("/icons/mcp.svg", Icons.class);
  public static final Icon Meta = IconLoader.getIcon("/icons/meta.svg", Icons.class);
  public static final Icon Mistral = IconLoader.getIcon("/icons/mistral.svg", Icons.class);
  public static final Icon Send = IconLoader.getIcon("/icons/send.svg", Icons.class);
  public static final Icon Sparkle = IconLoader.getIcon("/icons/sparkle.svg", Icons.class);
  public static final Icon Ollama = IconLoader.getIcon("/icons/ollama.svg", Icons.class);
  public static final Icon User = IconLoader.getIcon("/icons/user.svg", Icons.class);
  public static final Icon Tree = IconLoader.getIcon("/icons/tree.svg", Icons.class);
  public static final Icon Lightning = IconLoader.getIcon("/icons/lightning.svg", Icons.class);
  public static final Icon LightningDisabled =
      IconLoader.getIcon("/icons/lightning.svg", Icons.class);
  public static final Icon GreenCheckmark =
      IconLoader.getIcon("/icons/greenCheckmark.svg", Icons.class);
  public static final Icon SendToTheLeft =
      IconLoader.getIcon("/icons/sendToTheLeft.svg", Icons.class);
  public static final Icon OpenNewTab =
      IconLoader.getIcon("/icons/openNewTab.svg", Icons.class);
  public static final Icon AddFile =
      IconLoader.getIcon("/icons/addFile.svg", Icons.class);
  public static final Icon VCS =
      IconLoader.getIcon("/icons/vcs.svg", Icons.class);
  public static final Icon QuestionMark =
      IconLoader.getIcon("/icons/questionMark.svg", Icons.class);
  public static final Icon ListFiles =
      IconLoader.getIcon("/icons/listFiles.svg", Icons.class);
  public static final Icon InSelection =
      IconLoader.getIcon("/icons/inSelection.svg", Icons.class);
  public static final Icon StatusBarCompletionInProgress = new AnimatedIcon.Default();
}
