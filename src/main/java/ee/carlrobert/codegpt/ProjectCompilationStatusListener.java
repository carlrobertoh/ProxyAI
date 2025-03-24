package ee.carlrobert.codegpt;

import static ee.carlrobert.codegpt.completions.ConversationType.FIX_COMPILE_ERRORS;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.intellij.compiler.CompilerMessageImpl;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.completions.CompletionRequestUtil;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class ProjectCompilationStatusListener implements CompilationStatusListener {

  private final Project project;

  public ProjectCompilationStatusListener(Project project) {
    this.project = project;
  }

  @Override
  public void compilationFinished(
      boolean aborted,
      int errors,
      int warnings,
      @NotNull CompileContext compileContext) {
    var success = !ConfigurationSettings.getState().getCaptureCompileErrors()
        || (!aborted && errors == 0 && warnings == 0);
    if (success) {
      return;
    }
    if (errors > 0) {
      OverlayUtil.getDefaultNotification(
              CodeGPTBundle.get("notification.compilationError.description"),
              NotificationType.INFORMATION)
          .addAction(NotificationAction.createSimpleExpiring(
              CodeGPTBundle.get("notification.compilationError.okLabel"),
              () -> project.getService(ChatToolWindowContentManager.class)
                  .sendMessage(getMultiFileMessage(compileContext), FIX_COMPILE_ERRORS)))
          .addAction(NotificationAction.createSimpleExpiring(
              CodeGPTBundle.get("shared.notification.doNotShowAgain"),
              () -> ConfigurationSettings.getState().setCaptureCompileErrors(false)))
          .notify(project);
    }
  }

  private Message getMultiFileMessage(CompileContext compileContext) {
    var errorMapping = getErrorMapping(compileContext);
    var prompt = errorMapping.values().stream()
        .flatMap(Collection::stream)
        .collect(joining("\n\n"));

    var message = new Message("Fix the following compile errors:\n\n" + prompt);
    message.setReferencedFilePaths(errorMapping.keySet().stream()
        .map(ReferencedFile::filePath)
        .toList());
    message.setPrompt(CompletionRequestUtil.getPromptWithContext(
        new ArrayList<>(errorMapping.keySet()),
        prompt,
        new HashSet<>()));
    return message;
  }

  private HashMap<ReferencedFile, List<String>> getErrorMapping(CompileContext compileContext) {
    var errorMapping = new HashMap<ReferencedFile, List<String>>();
    for (var compilerMessage : compileContext.getMessages(CompilerMessageCategory.ERROR)) {
      var key = ReferencedFile.from(compilerMessage.getVirtualFile());
      var prevValue = errorMapping.get(key);
      if (prevValue == null) {
        prevValue = new ArrayList<>();
      }
      prevValue.add(getCompilerErrorDetails(compilerMessage));
      errorMapping.put(key, prevValue);
    }
    return errorMapping;
  }

  private String getCompilerErrorDetails(CompilerMessage compilerMessage) {
    if (compilerMessage instanceof CompilerMessageImpl compilerMessageImpl) {
      return format(
          "%s:%d:%d - `%s`",
          compilerMessage.getVirtualFile().getName(),
          compilerMessageImpl.getLine(),
          compilerMessageImpl.getColumn(),
          compilerMessage.getMessage());
    }
    return format(
        "%s - `%s`",
        compilerMessage.getVirtualFile().getName(),
        compilerMessage.getMessage());
  }
}
