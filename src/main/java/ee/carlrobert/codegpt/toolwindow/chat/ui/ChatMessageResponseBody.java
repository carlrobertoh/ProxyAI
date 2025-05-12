package ee.carlrobert.codegpt.toolwindow.chat.ui;

import static ee.carlrobert.codegpt.util.MarkdownUtil.convertMdToHtml;
import static java.lang.String.format;
import static javax.swing.event.HyperlinkEvent.EventType.ACTIVATED;

import com.intellij.icons.AllIcons;
import com.intellij.icons.AllIcons.General;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.actions.ActionType;
import ee.carlrobert.codegpt.events.AnalysisCompletedEventDetails;
import ee.carlrobert.codegpt.events.AnalysisFailedEventDetails;
import ee.carlrobert.codegpt.events.CodeGPTEvent;
import ee.carlrobert.codegpt.events.EventDetails;
import ee.carlrobert.codegpt.events.WebSearchEventDetails;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.settings.GeneralSettingsConfigurable;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel;
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.CopyAction;
import ee.carlrobert.codegpt.toolwindow.chat.parser.CompleteOutputParser;
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamOutputParser;
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse;
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse.StreamResponseType;
import ee.carlrobert.codegpt.toolwindow.ui.ResponseBodyProgressPanel;
import ee.carlrobert.codegpt.toolwindow.ui.WebpageList;
import ee.carlrobert.codegpt.ui.ThoughtProcessPanel;
import ee.carlrobert.codegpt.ui.UIUtil;
import ee.carlrobert.codegpt.util.EditorUtil;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.stream.Stream;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import org.jetbrains.annotations.NotNull;

public class ChatMessageResponseBody extends JPanel {

  private static final Logger LOG = Logger.getInstance(ChatMessageResponseBody.class);

  private final Project project;
  private final Disposable parentDisposable;
  private final StreamOutputParser streamOutputParser;
  private final boolean readOnly;
  private final DefaultListModel<WebSearchEventDetails> webpageListModel = new DefaultListModel<>();
  private final WebpageList webpageList = new WebpageList(webpageListModel);
  private final ResponseBodyProgressPanel progressPanel = new ResponseBodyProgressPanel();
  private final JPanel loadingLabel = createLoadingPanel();
  private final JPanel contentPanel = new JPanel();
  private ResponseEditorPanel currentlyProcessedEditorPanel;
  private JEditorPane currentlyProcessedTextPane;
  private JPanel webpageListPanel;

  private JPanel createLoadingPanel() {
    return new BorderLayoutPanel()
        .addToLeft(new JBLabel(
            CodeGPTBundle.get("toolwindow.chat.loading"),
            new AnimatedIcon.Default(),
            JLabel.LEFT))
        .withBorder(JBUI.Borders.empty(4, 0));
  }

  public ChatMessageResponseBody(Project project, Disposable parentDisposable) {
    this(project, false, false, false, false, parentDisposable);
  }

  public ChatMessageResponseBody(
      Project project,
      boolean readOnly,
      boolean webSearchIncluded,
      boolean withProgress,
      boolean withLoading,
      Disposable parentDisposable) {
    this.project = project;
    this.parentDisposable = parentDisposable;
    this.streamOutputParser = new StreamOutputParser();
    this.readOnly = readOnly;

    setLayout(new BorderLayout());
    setOpaque(false);

    contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
    contentPanel.setOpaque(false);
    add(contentPanel, BorderLayout.NORTH);

    loadingLabel.setVisible(withLoading);
    add(loadingLabel, BorderLayout.SOUTH);

    if (GeneralSettings.getSelectedService() == ServiceType.CODEGPT) {
      if (withProgress) {
        contentPanel.add(progressPanel);
      }

      if (webSearchIncluded) {
        webpageListPanel = createWebpageListPanel(webpageList);
        contentPanel.add(webpageListPanel);
      }
    }
  }

  public ChatMessageResponseBody withResponse(@NotNull String response) {
    try {
      for (var item : new CompleteOutputParser().parse(response)) {
        processResponse(item, false);

        currentlyProcessedTextPane = null;
        currentlyProcessedEditorPanel = null;
      }
    } catch (Exception e) {
      LOG.error("Something went wrong while processing input", e);
    }
    return this;
  }

  public void stopLoading() {
    loadingLabel.setVisible(false);
  }

  public void updateMessage(String partialMessage) {
    if (partialMessage.isEmpty()) {
      return;
    }

    var parsedResponse = streamOutputParser.parse(partialMessage);
    for (StreamParseResponse item : parsedResponse) {
      processResponse(item, true);
    }
  }

  public void displayMissingCredential() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (loadingLabel.isVisible()) {
        loadingLabel.setVisible(false);
      }

      if (webpageListPanel != null) {
        webpageListPanel.setVisible(false);
      }

      if (currentlyProcessedTextPane == null) {
        currentlyProcessedTextPane = createTextPane("");
        contentPanel.add(currentlyProcessedTextPane);
      }

      var message = "API key not provided. Open <a href=\"#\">Settings</a> to set one.";
      if (currentlyProcessedTextPane != null) {
        currentlyProcessedTextPane.setVisible(true);
        currentlyProcessedTextPane.setText(
            format("<html><p style=\"margin-top: 4px; margin-bottom: 8px;\">%s</p></html>",
                message));
        currentlyProcessedTextPane.addHyperlinkListener(e -> {
          if (e.getEventType() == ACTIVATED) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, GeneralSettingsConfigurable.class);
          }
        });
        hideCaret();
      }

      revalidate();
      repaint();
    });
  }

  public void displayQuotaExceeded() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (loadingLabel.isVisible()) {
        loadingLabel.setVisible(false);
      }

      if (webpageListPanel != null) {
        webpageListPanel.setVisible(false);
      }

      if (currentlyProcessedTextPane != null) {
        currentlyProcessedTextPane.setVisible(true);
        currentlyProcessedTextPane.setText("<html>"
            + "<p style=\"margin-top: 4px; margin-bottom: 8px;\">"
            + "You exceeded your current quota, please check your plan and billing details, "
            + "or <a href=\"#CHANGE_PROVIDER\">change</a> to a different LLM provider.</p>"
            + "</html>");

        currentlyProcessedTextPane.addHyperlinkListener(e -> {
          if (e.getEventType() == ACTIVATED) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, GeneralSettingsConfigurable.class);
            TelemetryAction.IDE_ACTION.createActionMessage()
                .property("action", ActionType.CHANGE_PROVIDER.name())
                .send();
          }
        });
        hideCaret();
      }

      revalidate();
      repaint();
    });
  }

  public void displayError(String message) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (loadingLabel.isVisible()) {
        loadingLabel.setVisible(false);
      }
      if (webpageListPanel != null) {
        webpageListPanel.setVisible(false);
      }

      var errorText = format(
          "<html><p style=\"margin-top: 4px; margin-bottom: 8px;\">%s</p></html>",
          message);
      if (currentlyProcessedTextPane == null) {
        contentPanel.add(createTextPane(errorText));
      } else {
        currentlyProcessedTextPane.setVisible(true);
        currentlyProcessedTextPane.setText(errorText);
      }
      hideCaret();

      revalidate();
      repaint();
    });
  }

  public void handleCodeGPTEvent(CodeGPTEvent codegptEvent) {
    ApplicationManager.getApplication()
        .invokeLater(() -> {
          var event = codegptEvent.getEvent();
          if (event.getDetails() instanceof WebSearchEventDetails webSearchEventDetails) {
            displayWebSearchItem(webSearchEventDetails);
            return;
          }

          switch (event.getType()) {
            case WEB_SEARCH_ITEM -> {
              if (event.getDetails() != null
                  && event.getDetails() instanceof WebSearchEventDetails eventDetails) {
                displayWebSearchItem(eventDetails);
              }
            }

            case ANALYZE_WEB_DOC_STARTED -> showWebDocsProgress();
            case ANALYZE_WEB_DOC_COMPLETED -> completeWebDocsProgress(event.getDetails());
            case ANALYZE_WEB_DOC_FAILED -> failWebDocsProgress(event.getDetails());
            case PROCESS_CONTEXT -> progressPanel.updateProgressDetails(event.getDetails());
            default -> {
            }
          }
        });
  }

  public void hideCaret() {
    if (currentlyProcessedTextPane != null) {
      currentlyProcessedTextPane.getCaret().setVisible(false);
    }
  }

  public void clear() {
    contentPanel.removeAll();
    streamOutputParser.clear();
    loadingLabel.setVisible(false);

    // TODO: First message might be code block
    prepareProcessingText(true);
    currentlyProcessedTextPane.setText(
        "<html><p style=\"margin-top: 4px; margin-bottom: 8px;\">&#8205;</p></html>");

    repaint();
    revalidate();
  }

  private void processThinkingOutput(String thoughtProcess) {
    progressPanel.setVisible(false);

    var thoughtProcessPanel = getExistingThoughtProcessPanel();
    if (thoughtProcessPanel == null) {
      thoughtProcessPanel = new ThoughtProcessPanel();
      thoughtProcessPanel.updateText(thoughtProcess);
      contentPanel.add(thoughtProcessPanel);
    } else {
      thoughtProcessPanel.updateText(thoughtProcess);
    }
  }

  private ThoughtProcessPanel getExistingThoughtProcessPanel() {
    return (ThoughtProcessPanel) Stream.of(contentPanel.getComponents())
        .filter(it -> it instanceof ThoughtProcessPanel)
        .findFirst()
        .orElse(null);
  }

  private void processResponse(StreamParseResponse item, boolean caretVisible) {
    if (item.getType() == StreamResponseType.THINKING) {
      processThinkingOutput(item.getContent());
      return;
    }

    var thoughtProcessPanel = getExistingThoughtProcessPanel();
    if (thoughtProcessPanel != null && !thoughtProcessPanel.isFinished()) {
      thoughtProcessPanel.setFinished();
    }

    if (item.getType() == StreamResponseType.CODE_CONTENT
        || item.getType() == StreamResponseType.CODE_HEADER) {
      processCode(item);
    } else {
      processText(item.getContent(), caretVisible);
    }
  }

  private void processCode(StreamParseResponse item) {
    var content = item.getContent();
    if (!content.isEmpty()) {
      if (currentlyProcessedEditorPanel == null) {
        prepareProcessingCode(item);
      }
      EditorUtil.updateEditorDocument(currentlyProcessedEditorPanel.getEditor(), content);
    }
  }

  private void processText(String markdownText, boolean caretVisible) {
    var html = convertMdToHtml(markdownText);
    if (currentlyProcessedTextPane == null) {
      prepareProcessingText(caretVisible);
    }
    currentlyProcessedTextPane.setText(html);
  }

  private void prepareProcessingText(boolean caretVisible) {
    currentlyProcessedEditorPanel = null;
    currentlyProcessedTextPane = createTextPane("", caretVisible);
    contentPanel.add(currentlyProcessedTextPane);
  }

  private void prepareProcessingCode(StreamParseResponse item) {
    hideCaret();
    currentlyProcessedTextPane = null;
    currentlyProcessedEditorPanel =
        new ResponseEditorPanel(project, item, readOnly, parentDisposable);
    contentPanel.add(currentlyProcessedEditorPanel);
  }

  private void displayWebSearchItem(WebSearchEventDetails details) {
    webpageListModel.addElement(details);
    webpageList.revalidate();
    webpageList.repaint();
  }

  private void showWebDocsProgress() {
    progressPanel.updateProgressContainer(
        CodeGPTBundle.get("chatMessageResponseBody.webDocs.startProgress.label"),
        null
    );
  }

  private void completeWebDocsProgress(EventDetails eventDetails) {
    if (eventDetails instanceof AnalysisCompletedEventDetails defaultEventDetails) {
      progressPanel.updateProgressContainer(
          defaultEventDetails.getDescription(),
          Icons.GreenCheckmark);
    }
  }

  private void failWebDocsProgress(EventDetails eventDetails) {
    if (eventDetails instanceof AnalysisFailedEventDetails failedEventDetails) {
      progressPanel.updateProgressContainer(failedEventDetails.getError(), General.Error);
    }
  }

  private JTextPane createTextPane(String text) {
    return createTextPane(text, false);
  }

  private JTextPane createTextPane(String text, boolean caretVisible) {
    var textPane = UIUtil.createTextPane(text, false, event -> {
      if (FileUtil.exists(event.getDescription()) && ACTIVATED.equals(event.getEventType())) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(event.getDescription());
        FileEditorManager.getInstance(project).openFile(Objects.requireNonNull(file), true);
        return;
      }

      UIUtil.handleHyperlinkClicked(event);
    });
    if (caretVisible) {
      textPane.getCaret().setVisible(true);
      textPane.setCaretPosition(textPane.getDocument().getLength());
    }
    textPane.setBorder(JBUI.Borders.empty());

    installPopupMenu(textPane);

    return textPane;
  }

  private void installPopupMenu(JTextPane textPane) {
    PopupHandler.installPopupMenu(textPane, new DefaultActionGroup(
        new AnAction(
            CodeGPTBundle.get("shared.copy"),
            CodeGPTBundle.get("shared.copyToClipboard"),
            AllIcons.Actions.Copy) {

          @Override
          public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
          }

          @Override
          public void actionPerformed(@NotNull AnActionEvent event) {
            textPane.copy();
            CopyAction.showCopyBalloon(event);
          }

          @Override
          public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(textPane.getSelectedText() != null);
          }
        }
    ), ActionPlaces.EDITOR_POPUP);
  }

  private static JPanel createWebpageListPanel(WebpageList webpageList) {
    var title = new JPanel(new BorderLayout());
    title.setOpaque(false);
    title.setBorder(JBUI.Borders.empty(8, 0));
    title.add(new JBLabel(CodeGPTBundle.get("chatMessageResponseBody.webPages.title"))
        .withFont(JBUI.Fonts.miniFont()), BorderLayout.LINE_START);
    var listPanel = new JPanel(new BorderLayout());
    listPanel.add(webpageList, BorderLayout.LINE_START);

    var panel = new JPanel(new BorderLayout());
    panel.add(title, BorderLayout.NORTH);
    panel.add(listPanel, BorderLayout.CENTER);
    return panel;
  }
}
