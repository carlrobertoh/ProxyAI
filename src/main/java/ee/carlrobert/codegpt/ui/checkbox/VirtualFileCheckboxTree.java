package ee.carlrobert.codegpt.ui.checkbox;

import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ui.tree.TreeUtil;
import ee.carlrobert.codegpt.settings.ProxyAISettingsService;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class VirtualFileCheckboxTree extends FileCheckboxTree {

  private final ProxyAISettingsService settingsService;

  public VirtualFileCheckboxTree(
      @NotNull VirtualFile[] rootFiles,
      @NotNull ProxyAISettingsService settingsService) {
    super(createFileTypesRenderer(), new CheckedTreeNode(null));
    this.settingsService = settingsService;
    var rootNode = (CheckedTreeNode) getModel().getRoot();
    for (VirtualFile file : rootFiles) {
      var childNode = createNode(file);
      if (childNode != null) {
        rootNode.add(childNode);
      }
    }
    setRootVisible(false);
    setShowsRootHandles(true);
    TreeUtil.expandAll(this);
  }

  public List<VirtualFile> getReferencedFiles() {
    var checkedNodes = getCheckedNodes(VirtualFile.class, Objects::nonNull);
    var files = new LinkedHashSet<VirtualFile>();
    Arrays.stream(checkedNodes)
        .filter(Objects::nonNull)
        .forEach(node -> collectVisibleFiles(node, files));
    if (files.size() > 1024) {
      OverlayUtil.showNotification("Too many files selected.", NotificationType.ERROR);
      throw new RuntimeException("Too many files selected");
    }
    return files.stream()
        .toList();
  }

  private void collectVisibleFiles(VirtualFile file, LinkedHashSet<VirtualFile> output) {
    if (!file.isValid() || !settingsService.isVirtualFileVisible(file)) {
      return;
    }
    if (!file.isDirectory()) {
      output.add(file);
      return;
    }
    Arrays.stream(file.getChildren())
        .forEach(child -> collectVisibleFiles(child, output));
  }

  private CheckedTreeNode createNode(VirtualFile file) {
    if (!settingsService.isVirtualFileVisible(file)) {
      return null;
    }

    CheckedTreeNode node = new CheckedTreeNode(file);
    if (file.isDirectory()) {
      VirtualFile[] children = file.getChildren();
      for (VirtualFile child : children) {
        var childNode = createNode(child);
        if (childNode != null) {
          node.add(childNode);
        }
      }
    }
    return node;
  }

  private static @NotNull FileCheckboxTreeCellRenderer createFileTypesRenderer() {
    return new FileCheckboxTreeCellRenderer() {
      @Override
      void updatePresentation(Object userObject) {
        if (userObject instanceof VirtualFile virtualFile) {
          if (virtualFile.isDirectory()) {
            getTextRenderer().append(virtualFile.getName());
            getTextRenderer().setIcon(PlatformIcons.FOLDER_ICON);
          } else {
            updateFilePresentation(getTextRenderer(), virtualFile);
          }
        } else if (userObject == null) {
          getTextRenderer().setIcon(AllIcons.Nodes.Folder);
          getTextRenderer().append("[root]");
        }
      }
    };
  }
}
