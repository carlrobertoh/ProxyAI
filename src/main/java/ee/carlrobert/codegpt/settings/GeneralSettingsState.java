package ee.carlrobert.codegpt.settings;

import com.intellij.openapi.application.ApplicationManager;
import ee.carlrobert.codegpt.settings.service.ModelRole;
import ee.carlrobert.codegpt.settings.service.ProviderChangeNotifier;
import ee.carlrobert.codegpt.settings.service.ServiceType;

import static ee.carlrobert.codegpt.settings.service.ModelRole.*;

public class GeneralSettingsState {

  private String displayName = "";
  private String avatarBase64 = "";
  private ServiceType selectedService = ServiceType.CODEGPT;
  private ServiceType codeCompletionService = ServiceType.CODEGPT;

  public String getDisplayName() {
    if (displayName == null || displayName.isEmpty()) {
      var systemUserName = System.getProperty("user.name");
      if (systemUserName == null || systemUserName.isEmpty()) {
        return "User";
      }
      return systemUserName;
    }
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getAvatarBase64() {
    return avatarBase64;
  }

  public void setAvatarBase64(String avatarBase64) {
    this.avatarBase64 = avatarBase64;
  }

  public ServiceType getSelectedService(ModelRole role) {
    switch (role) {
      case CHAT_ROLE -> {return selectedService;}
      case CODECOMPLETION_ROLE -> {return codeCompletionService;}
      default -> {throw new AssertionError();}
    }
  }

  public ServiceType getSelectedService() {
    return getSelectedService(CHAT_ROLE);
  }
  public ServiceType getSelectedCodeCompletionService() {
    return getSelectedService(CODECOMPLETION_ROLE);
  }

  public void setSelectedService(ModelRole role, ServiceType selectedService) {
    switch (role) {
      case CHAT_ROLE -> {
        this.selectedService = selectedService;

        ApplicationManager.getApplication()
                .getMessageBus()
                .syncPublisher(ProviderChangeNotifier.getTOPIC())
                .providerChanged(selectedService);
      }
      case CODECOMPLETION_ROLE -> {
        this.codeCompletionService = selectedService;
      }
      default -> {throw new AssertionError();}
    }
  }

  public void setSelectedService(ServiceType selectedService) {
    setSelectedService(CHAT_ROLE, selectedService);
  }
  public void setSelectedCodeCompletionService(ServiceType selectedService) {
    setSelectedService(CODECOMPLETION_ROLE, selectedService);
  }
}
