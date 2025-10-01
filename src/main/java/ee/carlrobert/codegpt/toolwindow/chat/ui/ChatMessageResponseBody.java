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
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.concurrency.AppExecutorUtil;
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
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.service.ModelSelectionService;
import ee.carlrobert.codegpt.settings.GeneralSettingsConfigurable;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.editor.ResponseEditorPanel;
import ee.carlrobert.codegpt.toolwindow.chat.editor.actions.CopyAction;
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DefaultHeaderPanel;
import ee.carlrobert.codegpt.toolwindow.chat.editor.header.DiffHeaderPanel;
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code;
import ee.carlrobert.codegpt.toolwindow.chat.parser.CodeEnd;
import ee.carlrobert.codegpt.toolwindow.chat.parser.CompleteMessageParser;
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting;
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace;
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchWaiting;
import ee.carlrobert.codegpt.toolwindow.chat.parser.Segment;
import ee.carlrobert.codegpt.toolwindow.chat.parser.SseMessageParser;
import ee.carlrobert.codegpt.toolwindow.chat.parser.Text;
import ee.carlrobert.codegpt.toolwindow.chat.parser.Thinking;
import ee.carlrobert.codegpt.toolwindow.ui.ResponseBodyProgressPanel;
import ee.carlrobert.codegpt.toolwindow.ui.WebpageList;
import ee.carlrobert.codegpt.ui.ThoughtProcessPanel;
import ee.carlrobert.codegpt.ui.UIUtil;
import ee.carlrobert.codegpt.util.EditorUtil;
import ee.carlrobert.codegpt.util.MarkdownUtil;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.stream.Stream;
import javax.swing.DefaultListModel;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.event.HyperlinkListener;
import kotlin.jvm.Synchronized;
import org.jetbrains.annotations.NotNull;

public class ChatMessageResponseBody extends JPanel {

  private static final Logger LOG = Logger.getInstance(ChatMessageResponseBody.class);

  private final Project project;
  private final Disposable parentDisposable;
  private final SseMessageParser streamOutputParser;
  private final boolean readOnly;
  private final DefaultListModel<WebSearchEventDetails> webpageListModel = new DefaultListModel<>();
  private final WebpageList webpageList = new WebpageList(webpageListModel);
  private final ResponseBodyProgressPanel progressPanel = new ResponseBodyProgressPanel();
  private final JPanel loadingLabel = createLoadingPanel();
  private final JPanel contentPanel =
      new JPanel(new VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 4, true, false));

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
    this.streamOutputParser = new SseMessageParser();
    this.readOnly = readOnly;

    setLayout(new BorderLayout());
    setOpaque(false);

    contentPanel.setOpaque(false);
    add(contentPanel, BorderLayout.NORTH);

    loadingLabel.setVisible(withLoading);
    add(loadingLabel, BorderLayout.SOUTH);

    if (ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT) == ServiceType.PROXYAI) {
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
      for (var item : new CompleteMessageParser().parse(response)) {
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
    for (Segment item : parsedResponse) {
      processResponse(item, true);
    }
  }

  public void displayMissingCredential() {
    String message = "API key not provided. Open <a href=\"#\">Settings</a> to set one.";
    displayErrorMessage(message, e -> {
      if (e.getEventType() == ACTIVATED) {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, GeneralSettingsConfigurable.class);
      }
    });
  }

  public void displayQuotaExceeded() {
    String message = "You exceeded your current quota, please check your plan and billing details, "
        + "or <a href=\"#CHANGE_PROVIDER\">change</a> to a different LLM provider.";
    displayErrorMessage(message, e -> {
      if (e.getEventType() == ACTIVATED) {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, GeneralSettingsConfigurable.class);
        TelemetryAction.IDE_ACTION.createActionMessage()
            .property("action", ActionType.CHANGE_PROVIDER.name())
            .send();
      }
    });
  }

  public void displayError(String message) {
    displayErrorMessage(message, null);
  }

  public void handleCodeGPTEvent(CodeGPTEvent codegptEvent) {
    ApplicationManager.getApplication().invokeLater(() -> {
      var event = codegptEvent.getEvent();
      if (event.getDetails() instanceof WebSearchEventDetails webSearchEventDetails) {
        displayWebSearchItem(webSearchEventDetails);
        return;
      }
      switch (event.getType()) {
        case WEB_SEARCH_ITEM -> {
          if (event.getDetails() instanceof WebSearchEventDetails details) {
            displayWebSearchItem(details);
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

    // Reset for the next incoming message
    prepareProcessingText(true);
    currentlyProcessedTextPane.setText(
        "<html><p style=\"margin-top: 4px; margin-bottom: 8px;\">&#8205;</p></html>");

    repaint();
    revalidate();
  }

  private void displayErrorMessage(String message, HyperlinkListener hyperlinkListener) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (loadingLabel.isVisible()) {
        loadingLabel.setVisible(false);
      }
      if (webpageListPanel != null) {
        webpageListPanel.setVisible(false);
      }

      String formattedMessage = format(
          "<html><p style=\"margin-top: 4px; margin-bottom: 8px;\">%s</p></html>", message);

      if (currentlyProcessedTextPane == null) {
        currentlyProcessedTextPane = createTextPane(formattedMessage, false);
        contentPanel.add(currentlyProcessedTextPane);
      }

      currentlyProcessedTextPane.setVisible(true);
      currentlyProcessedTextPane.setText(formattedMessage);

      if (hyperlinkListener != null) {
        for (HyperlinkListener listener : currentlyProcessedTextPane.getHyperlinkListeners()) {
          currentlyProcessedTextPane.removeHyperlinkListener(listener);
        }
        currentlyProcessedTextPane.addHyperlinkListener(hyperlinkListener);
      }

      hideCaret();

      revalidate();
      repaint();
    });
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

  private void processResponse(Segment item, boolean caretVisible) {
    if (item instanceof Thinking) {
      processThinkingOutput(item.getContent());
      return;
    }

    var thoughtProcessPanel = getExistingThoughtProcessPanel();
    if (thoughtProcessPanel != null && !thoughtProcessPanel.isFinished()) {
      thoughtProcessPanel.setFinished();
    }

    if (item instanceof CodeEnd) {
      if (currentlyProcessedEditorPanel != null) {
        handleHeaderOnCompletion(currentlyProcessedEditorPanel);
      }
      currentlyProcessedEditorPanel = null;
      return;
    }

    if (item instanceof SearchReplace searchReplace) {
      if (currentlyProcessedEditorPanel == null) {
        prepareProcessingCode(searchReplace);
      }
      if (currentlyProcessedEditorPanel != null) {
        currentlyProcessedEditorPanel.handleSearchReplace(searchReplace);
        handleHeaderOnCompletion(currentlyProcessedEditorPanel);
        return;
      }
    }

    if (item instanceof ReplaceWaiting replaceWaiting) {
      if (currentlyProcessedEditorPanel != null) {
        currentlyProcessedEditorPanel.handleReplace(replaceWaiting);
        return;
      }
    }

    if (item instanceof Code || item instanceof SearchWaiting) {
      processCode(item);
      return;
    }

    if (item instanceof Text) {
      processText(item.getContent(), caretVisible);
    }
  }

  private void processCode(Segment item) {
    var content = item.getContent();
    if (currentlyProcessedEditorPanel == null) {
      prepareProcessingCode(item);
      return;
    }

    var editor = currentlyProcessedEditorPanel.getEditor();
    if (item instanceof Code && editor != null) {
      EditorUtil.updateEditorDocument(editor, content);
    }
  }

  private void processText(String markdownText, boolean caretVisible) {
    if (markdownText == null || markdownText.isEmpty()) {
      return;
    }

    var html = convertMdToHtml(markdownText);
    if (currentlyProcessedTextPane == null) {
      prepareProcessingText(caretVisible);
    }
    currentlyProcessedTextPane.setText(html);
  }

  @Synchronized
  private void prepareProcessingText(boolean caretVisible) {
    currentlyProcessedEditorPanel = null;
    currentlyProcessedTextPane = createTextPane("", caretVisible);
    contentPanel.add(currentlyProcessedTextPane);
    contentPanel.revalidate();
    contentPanel.repaint();
  }

  @Synchronized
  private void prepareProcessingCode(Segment item) {
    hideCaret();
    currentlyProcessedTextPane = null;
    currentlyProcessedEditorPanel =
        new ResponseEditorPanel(project, item, readOnly, parentDisposable);
    contentPanel.add(currentlyProcessedEditorPanel);
    contentPanel.revalidate();
    contentPanel.repaint();
  }

  private void handleHeaderOnCompletion(ResponseEditorPanel editorPanel) {
    var editor = editorPanel.getEditor();
    if (editor != null) {
      var header = editor.getPermanentHeaderComponent();
      if (header instanceof DiffHeaderPanel diffHeaderPanel) {
        diffHeaderPanel.handleDone();
      } else if (header instanceof DefaultHeaderPanel defaultHeaderPanel) {
        defaultHeaderPanel.handleDone();
      }
    }
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
