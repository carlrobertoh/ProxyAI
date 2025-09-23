package ee.carlrobert.codegpt.completions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.completions.factory.CustomOpenAIRequest;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.service.ModelSelectionService;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.llm.client.DeserializationUtil;
import ee.carlrobert.llm.client.anthropic.completion.ClaudeCompletionRequest;
import ee.carlrobert.llm.client.codegpt.request.InlineEditRequest;
import ee.carlrobert.llm.client.codegpt.request.chat.ChatCompletionRequest;
import ee.carlrobert.llm.client.google.completion.GoogleCompletionRequest;
import ee.carlrobert.llm.client.llama.completion.LlamaCompletionRequest;
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionEventSourceListener;
import ee.carlrobert.llm.client.openai.completion.OpenAITextCompletionEventSourceListener;
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionRequest;
import ee.carlrobert.llm.client.openai.completion.response.OpenAIChatCompletionResponse;
import ee.carlrobert.llm.client.openai.completion.response.OpenAIChatCompletionResponseChoice;
import ee.carlrobert.llm.client.openai.completion.response.OpenAIChatCompletionResponseChoiceDelta;
import ee.carlrobert.llm.completion.CompletionEventListener;
import ee.carlrobert.llm.completion.CompletionRequest;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import okhttp3.Request;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSources;

@Service
public final class CompletionRequestService {

  private static final Logger LOG = Logger.getInstance(CompletionRequestService.class);

  private CompletionRequestService() {
  }

  public static CompletionRequestService getInstance() {
    return ApplicationManager.getApplication().getService(CompletionRequestService.class);
  }

  public EventSource getCustomOpenAICompletionAsync(
      Request customRequest,
      CompletionEventListener<String> eventListener) {
    var httpClient = CompletionClientProvider.getDefaultClientBuilder().build();
    return EventSources.createFactory(httpClient).newEventSource(
        customRequest,
        new OpenAITextCompletionEventSourceListener(eventListener));
  }

  public EventSource getCustomOpenAIChatCompletionAsync(
      Request customRequest,
      CompletionEventListener<String> eventListener) {
    var httpClient = CompletionClientProvider.getDefaultClientBuilder().build();
    return EventSources.createFactory(httpClient).newEventSource(
        customRequest,
        new OpenAIChatCompletionEventSourceListener(eventListener));
  }

  public String getLookupCompletion(LookupCompletionParameters params) {
    var serviceType =
        ModelSelectionService.getInstance().getServiceForFeature(FeatureType.LOOKUP);
    var request = CompletionRequestFactory
        .getFactory(serviceType)
        .createLookupRequest(params);
    return getChatCompletion(request, serviceType, FeatureType.LOOKUP);
  }

  public EventSource autoApplyAsync(
      AutoApplyParameters params,
      CompletionEventListener<String> eventListener) {
    var serviceType =
        ModelSelectionService.getInstance().getServiceForFeature(FeatureType.AUTO_APPLY);
    var request = CompletionRequestFactory
        .getFactory(serviceType)
        .createAutoApplyRequest(params);
    return getChatCompletionAsync(request, eventListener, serviceType, FeatureType.AUTO_APPLY);
  }

  public EventSource getCommitMessageAsync(
      CommitMessageCompletionParameters params,
      CompletionEventListener<String> eventListener) {
    var serviceType =
        ModelSelectionService.getInstance().getServiceForFeature(FeatureType.COMMIT_MESSAGE);
    var request = CompletionRequestFactory
        .getFactory(serviceType)
        .createCommitMessageRequest(params);
    return getChatCompletionAsync(request, eventListener, serviceType, FeatureType.COMMIT_MESSAGE);
  }

  public EventSource getInlineEditCompletionAsync(
      InlineEditCompletionParameters params,
      CompletionEventListener<String> eventListener) {
    var serviceType =
        ModelSelectionService.getInstance().getServiceForFeature(FeatureType.INLINE_EDIT);
    var request = CompletionRequestFactory
        .getFactory(serviceType)
        .createInlineEditRequest(params);
    return getChatCompletionAsync(request, eventListener, serviceType, FeatureType.INLINE_EDIT);
  }

  public EventSource getChatCompletionAsync(
      CompletionRequest request,
      CompletionEventListener<String> eventListener,
      ServiceType serviceType) {
    return getChatCompletionAsync(request, eventListener, serviceType, FeatureType.CHAT);
  }

  public EventSource getChatCompletionAsync(
      CompletionRequest request,
      CompletionEventListener<String> eventListener,
      ServiceType serviceType,
      FeatureType featureType) {
    if (request instanceof InlineEditRequest completionRequest) {
      return CompletionClientProvider.getCodeGPTClient()
          .getInlineEditAsync(completionRequest, eventListener);
    }
    if (request instanceof OpenAIChatCompletionRequest completionRequest) {
      return switch (serviceType) {
        case OPENAI -> CompletionClientProvider.getOpenAIClient()
            .getChatCompletionAsync(completionRequest, eventListener);
        case OLLAMA -> CompletionClientProvider.getOllamaClient()
            .getChatCompletionAsync(completionRequest, eventListener);
        case MISTRAL -> CompletionClientProvider.getMistralClient()
            .getChatCompletionAsync(completionRequest, eventListener);
        default -> throw new RuntimeException("Unknown service selected");
      };
    }
    if (request instanceof ChatCompletionRequest completionRequest) {
      return CompletionClientProvider.getCodeGPTClient()
          .getChatCompletionAsync(completionRequest, eventListener);
    }
    if (request instanceof CustomOpenAIRequest completionRequest) {
      return getCustomOpenAIChatCompletionAsync(completionRequest.getRequest(), eventListener);
    }
    if (request instanceof ClaudeCompletionRequest completionRequest) {
      return CompletionClientProvider.getClaudeClient().getCompletionAsync(
          completionRequest,
          eventListener);
    }
    if (request instanceof GoogleCompletionRequest completionRequest) {
      return CompletionClientProvider.getGoogleClient().getChatCompletionAsync(
          completionRequest,
          ModelSelectionService.getInstance().getModelForFeature(featureType, null),
          eventListener);
    }
    if (request instanceof LlamaCompletionRequest completionRequest) {
      return CompletionClientProvider.getLlamaClient().getChatCompletionAsync(
          completionRequest,
          eventListener);
    }

    throw new IllegalStateException("Unknown request type: " + request.getClass());
  }

  public String getChatCompletion(CompletionRequest request, ServiceType serviceType,
      FeatureType featureType) {
    if (request instanceof OpenAIChatCompletionRequest completionRequest) {
      var response = switch (serviceType) {
        case OPENAI -> CompletionClientProvider.getOpenAIClient()
            .getChatCompletion(completionRequest);
        case OLLAMA -> CompletionClientProvider.getOllamaClient()
            .getChatCompletion(completionRequest);
        case MISTRAL -> CompletionClientProvider.getMistralClient()
            .getChatCompletion(completionRequest);
        default -> throw new RuntimeException("Unknown service selected");
      };
      return tryExtractContent(response).orElseThrow();
    }
    if (request instanceof ChatCompletionRequest completionRequest) {
      var response =
          CompletionClientProvider.getCodeGPTClient().getChatCompletion(completionRequest);
      return tryExtractContent(response).orElseThrow();
    }
    if (request instanceof CustomOpenAIRequest completionRequest) {
      var httpClient = CompletionClientProvider.getDefaultClientBuilder().build();
      try (var response = httpClient.newCall(completionRequest.getRequest()).execute()) {
        return DeserializationUtil.mapResponse(response, OpenAIChatCompletionResponse.class)
            .getChoices().get(0)
            .getMessage()
            .getContent();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    if (request instanceof ClaudeCompletionRequest completionRequest) {
      return CompletionClientProvider.getClaudeClient()
          .getCompletion(completionRequest)
          .getContent().get(0)
          .getText();
    }
    if (request instanceof GoogleCompletionRequest completionRequest) {
      return CompletionClientProvider.getGoogleClient().getChatCompletion(
              completionRequest,
              ApplicationManager.getApplication()
                  .getService(ModelSelectionService.class)
                  .getModelForFeature(featureType, null))
          .getCandidates().get(0)
          .getContent().getParts().get(0)
          .getText();
    }
    if (request instanceof LlamaCompletionRequest completionRequest) {
      return CompletionClientProvider.getLlamaClient()
          .getChatCompletion(completionRequest)
          .getContent();
    }

    throw new IllegalStateException("Unknown request type: " + request.getClass());
  }

  public static boolean isRequestAllowed(FeatureType featureType) {
    try {
      return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var serviceType = ModelSelectionService.getInstance().getServiceForFeature(featureType);
            return isRequestAllowed(serviceType);
          })
          .get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean isRequestAllowed(ServiceType serviceType) {
    return switch (serviceType) {
      case OPENAI -> CredentialsStore.INSTANCE.isCredentialSet(CredentialKey.OpenaiApiKey.INSTANCE);
      case ANTHROPIC -> CredentialsStore.INSTANCE.isCredentialSet(
          CredentialKey.AnthropicApiKey.INSTANCE
      );
      case GOOGLE -> CredentialsStore.INSTANCE.isCredentialSet(CredentialKey.GoogleApiKey.INSTANCE);
      case MISTRAL ->
          CredentialsStore.INSTANCE.isCredentialSet(CredentialKey.MistralApiKey.INSTANCE);
      case PROXYAI, CUSTOM_OPENAI, LLAMA_CPP, OLLAMA -> true;
    };
  }

  /**
   * Content of the first choice.
   * <ul>
   *     <li>Search all choices which are not null</li>
   *     <li>Search all messages which are not null</li>
   *     <li>Use first content which is not null or blank (whitespace)</li>
   * </ul>
   *
   * @return First non-blank content or {@code Optional.empty()}
   */
  private Optional<String> tryExtractContent(OpenAIChatCompletionResponse response) {
    return Stream.ofNullable(response.getChoices())
        .flatMap(Collection::stream)
        .filter(Objects::nonNull)
        .map(OpenAIChatCompletionResponseChoice::getMessage)
        .filter(Objects::nonNull)
        .map(OpenAIChatCompletionResponseChoiceDelta::getContent)
        .filter(c -> c != null && !c.isBlank())
        .findFirst();
  }
}
