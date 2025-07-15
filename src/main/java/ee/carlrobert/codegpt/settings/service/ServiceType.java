package ee.carlrobert.codegpt.settings.service;

import ee.carlrobert.codegpt.CodeGPTBundle;
import java.util.HashMap;
import java.util.Map;

public enum ServiceType {
  PROXYAI("CODEGPT", "service.codegpt.title", "codegpt.chat.completion"),
  OPENAI("OPENAI", "service.openai.title", "chat.completion"),
  CUSTOM_OPENAI("CUSTOM_OPENAI", "service.custom.openai.title", "custom.openai.chat.completion"),
  ANTHROPIC("ANTHROPIC", "service.anthropic.title", "anthropic.chat.completion"),
  GOOGLE("GOOGLE", "service.google.title", "google.chat.completion"),
  LLAMA_CPP("LLAMA_CPP", "service.llama.title", "llama.chat.completion"),
  OLLAMA("OLLAMA", "service.ollama.title", "ollama.chat.completion");

  private final String code;
  private final String label;
  private final String completionCode;

  private static final Map<String, ServiceType> CLIENT_CODE_MAP = new HashMap<>();

  static {
    for (ServiceType type : values()) {
      CLIENT_CODE_MAP.put(type.getCompletionCode(), type);
    }
  }

  ServiceType(String code, String messageKey, String completionCode) {
    this.code = code;
    this.label = CodeGPTBundle.get(messageKey);
    this.completionCode = completionCode;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public String getCompletionCode() {
    return completionCode;
  }

  @Override
  public String toString() {
    return label;
  }

  public static ServiceType fromClientCode(String clientCode) {
    ServiceType serviceType = CLIENT_CODE_MAP.get(clientCode);
    if (serviceType == null) {
      throw new RuntimeException("Provided client code '" + clientCode + "' is not supported");
    }
    return serviceType;
  }
}
