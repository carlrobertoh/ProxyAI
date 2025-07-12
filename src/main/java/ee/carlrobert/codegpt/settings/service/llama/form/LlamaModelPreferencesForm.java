package ee.carlrobert.codegpt.settings.service.llama.form;

import static ee.carlrobert.codegpt.settings.service.llama.LlamaSettings.getLlamaModelsPath;
import static ee.carlrobert.codegpt.settings.service.llama.LlamaSettings.isModelExists;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;

import com.intellij.icons.AllIcons.General;
import com.intellij.ide.HelpTooltip;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.ui.components.AnActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate;
import ee.carlrobert.codegpt.completions.HuggingFaceModel;
import ee.carlrobert.codegpt.completions.llama.LlamaModel;
import ee.carlrobert.codegpt.completions.llama.LlamaModel.ModelSize;
import ee.carlrobert.codegpt.completions.llama.LlamaServerAgent;
import ee.carlrobert.codegpt.completions.llama.PromptTemplate;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ItemEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LlamaModelPreferencesForm {

  private static final String PREDEFINED_MODEL_FORM_CARD_CODE = "PredefinedModelSettings";
  private static final String CUSTOM_MODEL_FORM_CARD_CODE = "CustomModelSettings";
  private static final String GROUP_DOWNLOADED = "Downloaded";
  private static final String GROUP_NOT_DOWNLOADED = "Not Downloaded";

  private static final Map<Integer, Map<Integer, ModelDetails>> modelDetailsMap = Map.of(
      7, Map.of(
          3, new ModelDetails(3.30, 5.80),
          4, new ModelDetails(4.08, 6.58),
          5, new ModelDetails(4.78, 7.28)),
      13, Map.of(
          3, new ModelDetails(6.34, 8.84),
          4, new ModelDetails(7.87, 10.37),
          5, new ModelDetails(9.23, 11.73)),
      34, Map.of(
          3, new ModelDetails(16.28, 18.78),
          4, new ModelDetails(20.22, 22.72),
          5, new ModelDetails(23.84, 26.34)));

  private final TextFieldWithBrowseButton browsableCustomModelTextField;
  private final ComboBox<LlamaModel> modelComboBox;
  private final ComboBox<ModelSize> modelSizeComboBox;
  private final ComboBox<Object> huggingFaceModelComboBox;
  private final DefaultComboBoxModel<Object> huggingFaceComboBoxModel;
  private final JBLabel helpIcon;
  private final JPanel downloadModelActionLinkWrapper;
  private final JPanel modelActionsWrapper;
  private final JBLabel progressLabel;
  private final JBLabel modelDetailsLabel;
  private final CardLayout cardLayout;
  private final JBRadioButton predefinedModelRadioButton;
  private final JBRadioButton customModelRadioButton;
  private final ChatPromptTemplatePanel localPromptTemplatePanel;
  private final InfillPromptTemplatePanel infillPromptTemplatePanel;

  public LlamaModelPreferencesForm() {
    cardLayout = new CardLayout();
    progressLabel = createProgressLabel();
    helpIcon = new JBLabel(General.ContextHelp);
    huggingFaceComboBoxModel = new DefaultComboBoxModel<>();

    var llamaSettings = LlamaSettings.getCurrentState();
    var llm = llamaSettings.getHuggingFaceModel();
    var llamaModel = LlamaModel.findByHuggingFaceModel(llm);

    initializeHuggingFaceModel(llamaModel, llm);

    downloadModelActionLinkWrapper = createActionPanel();
    modelActionsWrapper = createActionPanel();
    updateModelActionsPanel(llm);

    modelDetailsLabel = new JBLabel();
    huggingFaceModelComboBox = createModelQuantizationComboBox();

    var llamaServerAgent = ApplicationManager.getApplication().getService(LlamaServerAgent.class);
    var serverRunning = llamaServerAgent.isServerRunning();

    huggingFaceModelComboBox.setEnabled(!serverRunning);

    var modelSizeComboBoxModel = new DefaultComboBoxModel<ModelSize>();
    var modelComboBoxModel = createLlamaModelComboBoxModel();

    modelComboBox = createModelComboBox(
        modelComboBoxModel, llamaModel, llm, llamaServerAgent, modelSizeComboBoxModel);
    modelComboBox.setEnabled(!serverRunning);

    modelSizeComboBox = createModelSizeComboBox(
        modelComboBoxModel,
        modelSizeComboBoxModel,
        llamaServerAgent);

    browsableCustomModelTextField = createBrowsableCustomModelTextField(!serverRunning);
    browsableCustomModelTextField.setText(llamaSettings.getCustomLlamaModelPath());

    localPromptTemplatePanel = new ChatPromptTemplatePanel(
        llamaSettings.getLocalModelPromptTemplate(),
        !serverRunning);

    predefinedModelRadioButton = new JBRadioButton("Use pre-defined model",
        !llamaSettings.isUseCustomModel());
    customModelRadioButton = new JBRadioButton("Use custom model",
        llamaSettings.isUseCustomModel());

    infillPromptTemplatePanel = new InfillPromptTemplatePanel(
        llamaSettings.getLocalModelInfillPromptTemplate(),
        !serverRunning);
  }

  public JPanel getForm() {
    JPanel finalPanel = new JPanel(new BorderLayout());
    finalPanel.add(createRadioButtonsPanel(), BorderLayout.NORTH);
    finalPanel.add(createFormPanelCards(), BorderLayout.CENTER);
    return finalPanel;
  }

  public void resetForm(LlamaSettingsState state) {
    huggingFaceComboBoxModel.setSelectedItem(state.getHuggingFaceModel());
    browsableCustomModelTextField.setText(state.getCustomLlamaModelPath());
    customModelRadioButton.setSelected(state.isUseCustomModel());
    localPromptTemplatePanel.setPromptTemplate(state.getLocalModelPromptTemplate());
    infillPromptTemplatePanel.setPromptTemplate(state.getLocalModelInfillPromptTemplate());
  }

  public void enableFields(boolean enabled) {
    modelComboBox.setEnabled(enabled);
    modelSizeComboBox.setEnabled(enabled);
    huggingFaceModelComboBox.setEnabled(enabled);
  }

  public TextFieldWithBrowseButton getBrowsableCustomModelTextField() {
    return browsableCustomModelTextField;
  }

  @SuppressWarnings("unchecked")
  public ComboBox<HuggingFaceModel> getHuggingFaceModelComboBox() {
    return (ComboBox<HuggingFaceModel>) (ComboBox<?>) huggingFaceModelComboBox;
  }

  @Nullable
  public HuggingFaceModel getSelectedModel() {
    var selected = huggingFaceComboBoxModel.getSelectedItem();
    return selected instanceof HuggingFaceModel ? (HuggingFaceModel) selected : null;
  }

  public String getCustomLlamaModelPath() {
    return browsableCustomModelTextField.getText();
  }

  public boolean isUseCustomLlamaModel() {
    return customModelRadioButton.isSelected();
  }

  public PromptTemplate getPromptTemplate() {
    return localPromptTemplatePanel.getPromptTemplate();
  }

  public InfillPromptTemplate getInfillPromptTemplate() {
    return infillPromptTemplatePanel.getPromptTemplate();
  }

  public String getActualModelPath() {
    if (isUseCustomLlamaModel()) {
      return getCustomLlamaModelPath();
    }
    var selectedModel = getSelectedModel();
    return selectedModel != null
        ? getLlamaModelsPath().resolve(selectedModel.getFileName()).toString()
        : "";
  }

  private JBLabel createProgressLabel() {
    var label = new JBLabel("");
    label.setBorder(JBUI.Borders.emptyLeft(4));
    label.setFont(JBUI.Fonts.smallFont());
    return label;
  }

  private JPanel createActionPanel() {
    var panel = new JPanel(new BorderLayout());
    panel.setBorder(JBUI.Borders.emptyLeft(4));
    return panel;
  }

  private void initializeHuggingFaceModel(LlamaModel llamaModel, HuggingFaceModel llm) {
    var selectableModels = llamaModel.getHuggingFaceModels().stream()
        .filter(model -> model.getParameterSize() == llm.getParameterSize())
        .toList();
    populateModelComboBoxWithGroups(selectableModels);
    huggingFaceComboBoxModel.setSelectedItem(llm);
  }

  private DefaultComboBoxModel<LlamaModel> createLlamaModelComboBoxModel() {
    var model = new DefaultComboBoxModel<LlamaModel>();
    model.addAll(LlamaModel.getSorted());
    return model;
  }

  private JPanel createFormPanelCards() {
    var formPanelCards = new JPanel(cardLayout);
    formPanelCards.setBorder(JBUI.Borders.emptyLeft(16));
    formPanelCards.add(createPredefinedModelForm(), PREDEFINED_MODEL_FORM_CARD_CODE);
    formPanelCards.add(createCustomModelForm(), CUSTOM_MODEL_FORM_CARD_CODE);

    cardLayout.show(
        formPanelCards,
        predefinedModelRadioButton.isSelected()
            ? PREDEFINED_MODEL_FORM_CARD_CODE
            : CUSTOM_MODEL_FORM_CARD_CODE);

    predefinedModelRadioButton.addActionListener(e ->
        cardLayout.show(formPanelCards, PREDEFINED_MODEL_FORM_CARD_CODE));
    customModelRadioButton.addActionListener(e ->
        cardLayout.show(formPanelCards, CUSTOM_MODEL_FORM_CARD_CODE));

    return formPanelCards;
  }

  private JPanel createRadioButtonsPanel() {
    var buttonGroup = new ButtonGroup();
    buttonGroup.add(predefinedModelRadioButton);
    buttonGroup.add(customModelRadioButton);

    var predefinedModelHelpText = createHelpText(
        "settingsConfigurable.service.llama.predefinedModel.comment");
    var customModelHelpText = createHelpText(
        "settingsConfigurable.service.llama.customModel.comment");

    var radioPanel = new JPanel();
    radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.PAGE_AXIS));
    radioPanel.add(predefinedModelRadioButton);
    radioPanel.add(predefinedModelHelpText);
    radioPanel.add(customModelRadioButton);
    radioPanel.add(customModelHelpText);
    return radioPanel;
  }

  private Component createHelpText(String bundleKey) {
    var helpText = ComponentPanelBuilder.createCommentComponent(
        CodeGPTBundle.get(bundleKey), true);
    helpText.setBorder(JBUI.Borders.empty(0, 28, 8, 0));
    return helpText;
  }

  private JPanel createCustomModelForm() {
    var customModelHelpText = ComponentPanelBuilder.createCommentComponent(
        CodeGPTBundle.get("settingsConfigurable.service.llama.customModelPath.comment"),
        true);
    customModelHelpText.setBorder(JBUI.Borders.empty(0, 4));

    return FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.customModelPath.label"),
            browsableCustomModelTextField)
        .addComponentToRightColumn(customModelHelpText)
        .addLabeledComponent(CodeGPTBundle.get("shared.promptTemplate"), localPromptTemplatePanel)
        .addComponentToRightColumn(localPromptTemplatePanel.getPromptTemplateHelpText())
        .addLabeledComponent(CodeGPTBundle.get("shared.infillPromptTemplate"),
            infillPromptTemplatePanel)
        .addComponentToRightColumn(infillPromptTemplatePanel.getPromptTemplateHelpText())
        .addVerticalGap(4)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  private JPanel createPredefinedModelForm() {
    var quantizationHelpText = ComponentPanelBuilder.createCommentComponent(
        CodeGPTBundle.get("settingsConfigurable.service.llama.quantization.comment"),
        true);
    quantizationHelpText.setBorder(JBUI.Borders.empty(0, 4));

    var modelComboBoxWrapper = createModelComboBoxWrapper();
    var huggingFaceModelComboBoxWrapper = createHuggingFaceModelComboBoxWrapper();

    return FormBuilder.createFormBuilder()
        .addLabeledComponent(CodeGPTBundle.get("settingsConfigurable.shared.model.label"),
            modelComboBoxWrapper)
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.modelSize.label"),
            modelSizeComboBox)
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.quantization.label"),
            huggingFaceModelComboBoxWrapper)
        .addComponentToRightColumn(quantizationHelpText)
        .addComponentToRightColumn(downloadModelActionLinkWrapper)
        .addComponentToRightColumn(progressLabel)
        .addComponentToRightColumn(modelActionsWrapper)
        .addVerticalGap(4)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  private JPanel createModelComboBoxWrapper() {
    var wrapper = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    wrapper.add(modelComboBox);
    wrapper.add(Box.createHorizontalStrut(8));
    wrapper.add(helpIcon);
    return wrapper;
  }

  private JPanel createHuggingFaceModelComboBoxWrapper() {
    var wrapper = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    wrapper.add(huggingFaceModelComboBox);
    wrapper.add(Box.createHorizontalStrut(8));
    wrapper.add(modelDetailsLabel);
    return wrapper;
  }

  private String getHuggingFaceModelDetailsHtml(HuggingFaceModel model) {
    int parameterSize = model.getParameterSize();
    int quantization = model.getQuantization();

    var details = modelDetailsMap.getOrDefault(parameterSize, emptyMap()).get(quantization);
    if (details == null && model.getDownloadSize() == null) {
      return "";
    }
    if (details == null) {
      return format("<html>"
          + "<p style=\"margin: 0\"><small>File Size: <strong>%.2f GB</strong></small></p>"
          + "</html>", model.getDownloadSize());
    }
    return format("<html>"
        + "<p style=\"margin: 0\"><small>File Size: <strong>%.2f GB</strong></small></p>"
        + "<p style=\"margin: 0\"><small>Max RAM Required: <strong>%.2f GB</strong></small></p>"
        + "</html>", details.fileSize, details.maxRAMRequired);
  }

  private ComboBox<LlamaModel> createModelComboBox(
      ComboBoxModel<LlamaModel> llamaModelEnumComboBoxModel,
      LlamaModel llamaModel,
      HuggingFaceModel llm,
      LlamaServerAgent llamaServerAgent,
      DefaultComboBoxModel<ModelSize> modelSizeComboBoxModel) {
    var comboBox = new ComboBox<>(llamaModelEnumComboBoxModel);
    comboBox.setPreferredSize(new Dimension(280, comboBox.getPreferredSize().height));
    comboBox.setSelectedItem(llamaModel);
    initializeModelSizes(llamaModel, llm, modelSizeComboBoxModel);

    comboBox.addItemListener(
        e -> handleModelSelection(e, modelSizeComboBoxModel, llamaServerAgent));
    return comboBox;
  }

  private void handleModelSelection(
      ItemEvent e,
      DefaultComboBoxModel<ModelSize> modelSizeComboBoxModel,
      LlamaServerAgent llamaServerAgent) {
    var selectedModel = (LlamaModel) e.getItem();
    var hfm = selectedModel.getLastExistingModelOrFirst();
    var modelSize = initializeModelSizes(selectedModel, hfm, modelSizeComboBoxModel);
    var huggingFaceModels = selectedModel.filterSelectedModelsBySize(modelSize);
    populateModelComboBoxWithGroups(huggingFaceModels);
    huggingFaceComboBoxModel.setSelectedItem(hfm);
    modelSizeComboBox.setEnabled(
        modelSizeComboBox.getModel().getSize() > 1 && !llamaServerAgent.isServerRunning());
  }

  private static ModelSize initializeModelSizes(
      LlamaModel llamaModel,
      HuggingFaceModel hfm,
      DefaultComboBoxModel<ModelSize> modelSizeComboBoxModel) {
    var modelSizes = llamaModel.getSortedUniqueModelSizes();
    modelSizeComboBoxModel.removeAllElements();
    modelSizeComboBoxModel.addAll(modelSizes);

    var selectedModelSize = findMatchingModelSize(modelSizes, hfm.getParameterSize())
        .orElse(modelSizes.get(0));

    modelSizeComboBoxModel.setSelectedItem(selectedModelSize);
    return selectedModelSize;
  }

  private static Optional<ModelSize> findMatchingModelSize(List<ModelSize> modelSizes,
      int parameterSize) {
    return modelSizes.stream()
        .filter(ms -> ms.size() == parameterSize)
        .findFirst();
  }

  private ComboBox<ModelSize> createModelSizeComboBox(
      ComboBoxModel<LlamaModel> llamaModelComboBoxModel,
      DefaultComboBoxModel<ModelSize> modelSizeComboBoxModel,
      LlamaServerAgent llamaServerAgent) {
    var comboBox = new ComboBox<>(modelSizeComboBoxModel);
    comboBox.setPreferredSize(modelComboBox.getPreferredSize());
    comboBox.setSelectedItem(modelSizeComboBoxModel.getSelectedItem());
    comboBox.setEnabled(
        modelSizeComboBoxModel.getSize() > 1 && !llamaServerAgent.isServerRunning());

    comboBox.addItemListener(e -> handleModelSizeSelection(
        e, llamaModelComboBoxModel, modelSizeComboBoxModel, llamaServerAgent));

    return comboBox;
  }

  private void handleModelSizeSelection(
      java.awt.event.ItemEvent e,
      ComboBoxModel<LlamaModel> llamaModelComboBoxModel,
      DefaultComboBoxModel<ModelSize> modelSizeComboBoxModel,
      LlamaServerAgent llamaServerAgent) {
    var selectedModel = (LlamaModel) llamaModelComboBoxModel.getSelectedItem();
    if (selectedModel == null) {
      return;
    }

    var selectedSize = (ModelSize) modelSizeComboBoxModel.getSelectedItem();
    if (selectedSize == null) {
      return;
    }

    var models = selectedModel.filterSelectedModelsBySize(selectedSize);
    modelSizeComboBox.setEnabled(
        modelSizeComboBoxModel.getSize() > 1 && !llamaServerAgent.isServerRunning());

    if (!models.isEmpty()) {
      populateModelComboBoxWithGroups(models);
      huggingFaceComboBoxModel.setSelectedItem(models.get(0));
    }
  }

  private ComboBox<Object> createModelQuantizationComboBox() {
    var comboBox = new ComboBox<>(huggingFaceComboBoxModel);
    var selectedItem = comboBox.getSelectedItem();
    if (selectedItem instanceof HuggingFaceModel) {
      updateFromModelState((HuggingFaceModel) selectedItem);
    }

    comboBox.addItemListener(this::handleQuantizationSelection);
    comboBox.setRenderer(new ModelQuantizationRenderer());
    return comboBox;
  }

  private void handleQuantizationSelection(java.awt.event.ItemEvent e) {
    var item = e.getItem();
    if (item instanceof HuggingFaceModel hfm) {
      updateFromModelState(hfm);
    } else if (item instanceof String) {
      SwingUtilities.invokeLater(this::selectFirstAvailableModel);
    }
  }

  private void selectFirstAvailableModel() {
    for (int i = 0; i < huggingFaceComboBoxModel.getSize(); i++) {
      var item = huggingFaceComboBoxModel.getElementAt(i);
      if (item instanceof HuggingFaceModel) {
        huggingFaceModelComboBox.setSelectedItem(item);
        break;
      }
    }
  }

  private void updateFromModelState(HuggingFaceModel selectedModel) {
    var modelExists = isModelExists(selectedModel);
    updateModelHelpTooltip(selectedModel);
    modelDetailsLabel.setText(getHuggingFaceModelDetailsHtml(selectedModel));
    updateModelActionsPanel(selectedModel);
  }

  private TextFieldWithBrowseButton createBrowsableCustomModelTextField(boolean enabled) {
    var browseButton = new TextFieldWithBrowseButton();
    browseButton.setEnabled(enabled);

    var fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("gguf");
    fileChooserDescriptor.setForcedToUseIdeaFileChooser(true);
    fileChooserDescriptor.setHideIgnored(false);
    browseButton.addBrowseFolderListener(new TextBrowseFolderListener(fileChooserDescriptor));
    return browseButton;
  }

  private AnActionLink createCancelDownloadLink(
      JBLabel progressLabel,
      JPanel actionLinkWrapper,
      ProgressIndicator progressIndicator) {
    return new AnActionLink(
        CodeGPTBundle.get("settingsConfigurable.service.llama.cancelDownloadLink.label"),
        new AnAction() {
          @Override
          public void actionPerformed(@NotNull AnActionEvent e) {
            SwingUtilities.invokeLater(() -> {
              configureFieldsForDownloading(false);
              updateActionLink(
                  actionLinkWrapper,
                  createDownloadModelLink(progressLabel, actionLinkWrapper));
              progressIndicator.cancel();
            });
          }
        });
  }

  private void updateActionLink(JPanel actionLinkWrapper, AnActionLink actionLink) {
    actionLinkWrapper.removeAll();
    var flowPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    flowPanel.add(actionLink);
    flowPanel.add(Box.createHorizontalStrut(16));
    flowPanel.add(createOpenFolderLink());
    actionLinkWrapper.add(flowPanel, BorderLayout.WEST);
    actionLinkWrapper.revalidate();
    actionLinkWrapper.repaint();
  }

  void configureFieldsForDownloading(boolean downloading) {
    progressLabel.setText("");
    progressLabel.setVisible(downloading);
    modelComboBox.setEnabled(!downloading);
    modelSizeComboBox.setEnabled(!downloading);
    huggingFaceModelComboBox.setEnabled(!downloading);
  }

  private AnActionLink createDownloadModelLink(
      JBLabel progressLabel,
      JPanel actionLinkWrapper) {
    return new AnActionLink(
        CodeGPTBundle.get("settingsConfigurable.service.llama.downloadModelLink.label"),
        new DownloadModelAction(
            progressIndicator -> SwingUtilities.invokeLater(() -> {
              configureFieldsForDownloading(true);
              updateActionLink(
                  actionLinkWrapper,
                  createCancelDownloadLink(progressLabel, actionLinkWrapper, progressIndicator));
            }),
            () -> SwingUtilities.invokeLater(() -> {
              configureFieldsForDownloading(false);
              var downloadedModel = getSelectedModel();
              if (downloadedModel != null) {
                LlamaSettings.getCurrentState().setHuggingFaceModel(downloadedModel);
                updateFromModelState(downloadedModel);
              }
            }),
            (error) -> {
              throw new RuntimeException(error);
            },
            (text) -> SwingUtilities.invokeLater(() -> progressLabel.setText(text)),
            getHuggingFaceComboBoxModel()), "unknown");
  }

  @SuppressWarnings("unchecked")
  private DefaultComboBoxModel<HuggingFaceModel> getHuggingFaceComboBoxModel() {
    return (DefaultComboBoxModel<HuggingFaceModel>) (DefaultComboBoxModel<?>) huggingFaceComboBoxModel;
  }

  private void updateModelHelpTooltip(HuggingFaceModel model) {
    helpIcon.setToolTipText(null);
    var llamaModel = LlamaModel.findByHuggingFaceModel(model);
    new HelpTooltip()
        .setTitle(llamaModel.getLabel())
        .setDescription(llamaModel.getDescription())
        .setBrowserLink(
            CodeGPTBundle.get("settingsConfigurable.service.llama.linkToModel.label"),
            model.getHuggingFaceURL())
        .installOn(helpIcon);
  }

  private void populateModelComboBoxWithGroups(List<HuggingFaceModel> models) {
    huggingFaceComboBoxModel.removeAllElements();

    var downloadedModels = models.stream()
        .filter(LlamaSettings::isModelExists)
        .toList();
    var notDownloadedModels = models.stream()
        .filter(model -> !isModelExists(model))
        .toList();

    if (!downloadedModels.isEmpty()) {
      huggingFaceComboBoxModel.addElement(GROUP_DOWNLOADED);
      downloadedModels.forEach(huggingFaceComboBoxModel::addElement);
    }

    if (!notDownloadedModels.isEmpty()) {
      huggingFaceComboBoxModel.addElement(GROUP_NOT_DOWNLOADED);
      notDownloadedModels.forEach(huggingFaceComboBoxModel::addElement);
    }
  }

  private void updateModelActionsPanel(HuggingFaceModel model) {
    modelActionsWrapper.removeAll();
    downloadModelActionLinkWrapper.removeAll();

    var flowPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    if (isModelExists(model)) {
      flowPanel.add(createDeleteModelLink(model));
    } else {
      flowPanel.add(createDownloadModelLink(progressLabel, downloadModelActionLinkWrapper));
    }
    flowPanel.add(Box.createHorizontalStrut(16));
    flowPanel.add(createOpenFolderLink());

    downloadModelActionLinkWrapper.setVisible(false);
    modelActionsWrapper.setVisible(true);
    modelActionsWrapper.add(flowPanel, BorderLayout.WEST);

    modelActionsWrapper.revalidate();
    modelActionsWrapper.repaint();
    downloadModelActionLinkWrapper.revalidate();
    downloadModelActionLinkWrapper.repaint();
  }

  private AnActionLink createDeleteModelLink(HuggingFaceModel model) {
    return new AnActionLink("Delete Model", new AnAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        var modelName = model.getFileName();
        var result = Messages.showYesNoDialog(
            "Are you sure you want to delete the model '" + modelName + "'?",
            "Delete Model",
            Messages.getQuestionIcon()
        );

        if (result == Messages.YES) {
          var modelFile = getLlamaModelsPath().resolve(modelName).toFile();
          if (modelFile.exists() && modelFile.delete()) {
            updateFromModelState(model);
            Messages.showInfoMessage("Model '" + modelName + "' has been deleted.",
                "Model Deleted");
          } else {
            Messages.showErrorDialog("Failed to delete the model file.", "Delete Failed");
          }
        }
      }
    });
  }

  private AnActionLink createOpenFolderLink() {
    return new AnActionLink("Open in Folder", new AnAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        try {
          var modelsPath = getLlamaModelsPath().toFile();
          if (!modelsPath.exists() && !modelsPath.mkdirs()) {
            Messages.showErrorDialog("Failed to create models directory.", "Error");
            return;
          }

          if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(modelsPath);
          } else {
            Messages.showErrorDialog("Desktop operations are not supported on this system.",
                "Error");
          }
        } catch (IOException ex) {
          Messages.showErrorDialog("Failed to open the models folder: " + ex.getMessage(), "Error");
        }
      }
    });
  }

  private static class ModelQuantizationRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
        boolean isSelected, boolean cellHasFocus) {

      if (value instanceof String groupHeader) {
        var label = (JLabel) super.getListCellRendererComponent(list, groupHeader, index, false,
            false);
        label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        label.setEnabled(false);
        if (index > 0) {
          label.setBorder(JBUI.Borders.compound(
              JBUI.Borders.customLine(JBUI.CurrentTheme.Popup.separatorColor(), 1, 0, 0, 0),
              JBUI.Borders.empty(4, 4)
          ));
        } else {
          label.setBorder(JBUI.Borders.empty(4, 4));
        }
        return label;
      }

      if (value instanceof HuggingFaceModel hfm) {
        var item = hfm.getQuantizationLabel();
        return super.getListCellRendererComponent(list, item, index, isSelected, cellHasFocus);
      }

      return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
    }
  }

  private record ModelDetails(double fileSize, double maxRAMRequired) {

  }
}