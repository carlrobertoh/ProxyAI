package ee.carlrobert.codegpt.toolwindow.chat.editor;

import static ee.carlrobert.codegpt.util.file.FileUtil.findLanguageExtensionMapping;
import static java.lang.String.format;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.event.BulkAwareDocumentListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.actions.toolwindow.ReplaceCodeInMainEditorAction;
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse;
import ee.carlrobert.codegpt.util.EditorUtil;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.jetbrains.annotations.NotNull;

public class ResponseEditorPanel extends JPanel implements Disposable {

  private final Editor editor;
  private final JPanel expandLinkPanel;
  private boolean expandLinkAdded = false;

  public ResponseEditorPanel(
      Project project,
      StreamParseResponse item,
      boolean readOnly,
      Disposable disposableParent) {
    super(new BorderLayout());
    setBorder(JBUI.Borders.empty(8, 0));
    setOpaque(false);

    editor = EditorUtil.createEditor(
        project,
        findLanguageExtensionMapping(item.getLanguage()).getValue(),
        StringUtil.convertLineSeparators(item.getContent()));
    var group = new DefaultActionGroup();
    group.add(new ReplaceCodeInMainEditorAction());
    String originalGroupId = ((EditorEx) editor).getContextMenuGroupId();
    if (originalGroupId != null) {
      ActionManager actionManager = ActionManager.getInstance();
      AnAction originalGroup = actionManager.getAction(originalGroupId);
      if (originalGroup instanceof ActionGroup) {
        group.addAll(((ActionGroup) originalGroup).getChildren(null, actionManager));
      }
    }

    configureEditor(
        project,
        (EditorEx) editor,
        readOnly,
        new ContextMenuPopupHandler.Simple(group),
        item.getFilePath(),
        findLanguageExtensionMapping(item.getLanguage()).getKey());
    add(editor.getComponent(), BorderLayout.CENTER);

    expandLinkPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
    expandLinkPanel.setOpaque(false);
    expandLinkPanel.setBorder(
        JBUI.Borders.compound(
            JBUI.Borders.customLine(ColorUtil.fromHex("#48494b"), 0, 1, 1, 1),
            JBUI.Borders.empty(0)));
    expandLinkPanel.add(createExpandLink((EditorEx) editor));

    editor.getDocument().addDocumentListener(new BulkAwareDocumentListener.Simple() {
      @Override
      public void documentChanged(@NotNull DocumentEvent event) {
        checkLineCountAndUpdateUI();
      }
    });

    checkLineCountAndUpdateUI();

    Disposer.register(disposableParent, this);
  }

  private void checkLineCountAndUpdateUI() {
    int lineCount = editor.getDocument().getLineCount();
    if (lineCount > 8 && !expandLinkAdded) {
      add(expandLinkPanel, BorderLayout.SOUTH);
      expandLinkAdded = true;
      revalidate();
      repaint();
    }
  }

  @Override
  public void dispose() {
    EditorFactory.getInstance().releaseEditor(editor);
  }

  public Editor getEditor() {
    return editor;
  }

  private void configureEditor(
      Project project,
      EditorEx editorEx,
      boolean readOnly,
      ContextMenuPopupHandler popupHandler,
      String filePath,
      String language) {
    if (readOnly) {
      editorEx.setOneLineMode(true);
      editorEx.setHorizontalScrollbarVisible(false);
    }
    editorEx.installPopupHandler(popupHandler);
    editorEx.setColorsScheme(EditorColorsManager.getInstance().getSchemeForCurrentUITheme());

    var settings = editorEx.getSettings();
    settings.setAdditionalColumnsCount(0);
    settings.setAdditionalLinesCount(0);
    settings.setAdditionalPageAtBottom(false);
    settings.setVirtualSpace(false);
    settings.setUseSoftWraps(false);
    settings.setLineMarkerAreaShown(false);
    settings.setGutterIconsShown(false);
    settings.setLineNumbersShown(false);

    editorEx.getGutterComponentEx().setVisible(true);
    editorEx.getGutterComponentEx().getParent().setVisible(false);
    editorEx.setVerticalScrollbarVisible(false);
    editorEx.getContentComponent().setBorder(JBUI.Borders.emptyLeft(4));
    editorEx.setBorder(IdeBorderFactory.createBorder(ColorUtil.fromHex("#48494b")));

    editorEx.setPermanentHeaderComponent(
        new HeaderPanel(project, editorEx, filePath, language, readOnly));
    editorEx.setHeaderComponent(null);
  }

  private String getLinkText(boolean expanded) {
    return expanded
        ? format(
        CodeGPTBundle.get("toolwindow.chat.editor.action.expand"),
        ((EditorEx) editor).getDocument().getLineCount() - 1)
        : CodeGPTBundle.get("toolwindow.chat.editor.action.collapse");
  }

  private ActionLink createExpandLink(EditorEx editorEx) {
    var linkText = getLinkText(editorEx.isOneLineMode());
    var expandLink = new ActionLink(
        linkText,
        event -> {
          var oneLineMode = editorEx.isOneLineMode();
          var source = (ActionLink) event.getSource();
          source.setText(getLinkText(!oneLineMode));
          source.setIcon(oneLineMode ? Icons.CollapseAll : Icons.ExpandAll);

          editorEx.setOneLineMode(!oneLineMode);
          editorEx.setHorizontalScrollbarVisible(oneLineMode);
          editorEx.getContentComponent().revalidate();
          editorEx.getContentComponent().repaint();
        });
    expandLink.setIcon(editorEx.isOneLineMode() ? Icons.ExpandAll : Icons.CollapseAll);
    expandLink.setFont(JBUI.Fonts.smallFont());
    expandLink.setForeground(JBColor.GRAY);
    expandLink.setHorizontalAlignment(SwingConstants.CENTER);
    return expandLink;
  }
}