package ee.carlrobert.codegpt.completions.custom;

import static ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CUSTOM_SERVICE_API_KEY;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ee.carlrobert.codegpt.codecompletions.InfillRequestDetails;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState;
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.Request;
import okhttp3.RequestBody;

public class CustomServiceRequestBuilder {
  public static Request buildCompletionRequest(
      CustomServiceSettingsState customConfiguration,
      List<String> messages) {
    var settings = customConfiguration.getCompletionSettings();

    var body = settings.getBody();
    body = replacePlaceholder(body, "$OPENAI_MESSAGES", messages);

    return buildRequest(
        settings.getUrl(),
        settings.getHeaders(),
        body,
        true);
  }

  public static Request buildCompletionRequest(
      CustomServiceSettingsState customConfiguration,
      InfillRequestDetails details) {
    var settings = customConfiguration.getCompletionSettings();

    var body = settings.getBody();
    body = replacePlaceholder(body, "$OPENAI_PREFIX", details.getPrefix());
    body = replacePlaceholder(body, "$OPENAI_SUFFIX", details.getSuffix());
    body.put("stop", "\n"); // Autocomplete one line at a time.

    return buildRequest(
        settings.getUrl(),
        settings.getHeaders(),
        body,
        true);
  }

  public static Request buildChatCompletionRequest(
      CustomServiceSettingsState customConfiguration,
      List<OpenAIChatCompletionMessage> messages) {
    var settings = customConfiguration.getChatCompletionSettings();

    var body = settings.getBody();
    body = replacePlaceholder(body, "$OPENAI_MESSAGES", messages);

    return buildRequest(
        settings.getUrl(),
        settings.getHeaders(),
        body,
        true);
  }

  public static Request buildLookupCompletionRequest(
      CustomServiceSettingsState customConfiguration,
      List<OpenAIChatCompletionMessage> messages) {
    var settings = customConfiguration.getChatCompletionSettings();

    var body = settings.getBody();
    body = replacePlaceholder(body, "$OPENAI_MESSAGES", messages);

    return buildRequest(
        settings.getUrl(),
        settings.getHeaders(),
        body,
        false);
  }

  private static Map<String, Object> replacePlaceholder(
      Map<String, Object> body,
      String placeholder,
      Object newValue) {
    return body.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
              var value = entry.getValue();
              if (value instanceof String string && placeholder.equals(string.trim())) {
                return newValue;
              }
              return value;
            }
        ));
  }

  private static <T> Request buildRequest(
      String url,
      Map<String, String> headers,
      Map<String, ?> body,
      boolean streamRequest) {
    var requestBuilder = new Request.Builder().url(url.trim());
    var credential = CredentialsStore.INSTANCE.getCredential(CUSTOM_SERVICE_API_KEY);
    for (var entry : headers.entrySet()) {
      String value = entry.getValue();
      if (credential != null && value.contains("$CUSTOM_SERVICE_API_KEY")) {
        value = value.replace("$CUSTOM_SERVICE_API_KEY", credential);
      }
      requestBuilder.addHeader(entry.getKey(), value);
    }

    var transformedBody = body.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
              if (!streamRequest && "stream".equals(entry.getKey())) {
                return false;
              }

              return entry.getValue();
            }
        ));

    try {
      var requestBody = RequestBody.create(new ObjectMapper()
          .writerWithDefaultPrettyPrinter()
          .writeValueAsString(transformedBody)
          .getBytes(StandardCharsets.UTF_8));
      return requestBuilder.post(requestBody).build();
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

}