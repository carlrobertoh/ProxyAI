package ee.carlrobert.codegpt.settings.service.llama.form;

import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate;
import ee.carlrobert.codegpt.codecompletions.InfillRequest;

public class InfillPromptTemplatePanel extends BasePromptTemplatePanel<InfillPromptTemplate> {

  public InfillPromptTemplatePanel(
      InfillPromptTemplate initiallySelectedTemplate,
      boolean enabled) {
    super(
        InfillPromptTemplate.class,
        initiallySelectedTemplate,
        enabled,
        "settingsConfigurable.service.llama.infillTemplate.comment");
  }

  @Override
  protected String buildPromptDescription(InfillPromptTemplate template) {
    return template.buildPrompt(new InfillRequest
        .Builder("PREFIX", "SUFFIX", 0)
        .build());
  }
}
