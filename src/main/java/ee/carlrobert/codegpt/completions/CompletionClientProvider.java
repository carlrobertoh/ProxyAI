package ee.carlrobert.codegpt.completions;

import static ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential;

import com.intellij.openapi.application.ApplicationManager;
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey;
import ee.carlrobert.codegpt.settings.advanced.AdvancedSettings;
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings;
import ee.carlrobert.codegpt.settings.service.azure.AzureSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings;
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings;
import ee.carlrobert.llm.client.anthropic.ClaudeClient;
import ee.carlrobert.llm.client.azure.AzureClient;
import ee.carlrobert.llm.client.azure.AzureCompletionRequestParams;
import ee.carlrobert.llm.client.codegpt.CodeGPTClient;
import ee.carlrobert.llm.client.google.GoogleClient;
import ee.carlrobert.llm.client.llama.LlamaClient;
import ee.carlrobert.llm.client.ollama.OllamaClient;
import ee.carlrobert.llm.client.openai.OpenAIClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;

public class CompletionClientProvider {

  public static CodeGPTClient getCodeGPTClient() {
    return new CodeGPTClient(
        getCredential(CredentialKey.CodeGptApiKey.INSTANCE),
        getDefaultClientBuilder());
  }

  public static OpenAIClient getOpenAIClient() {
    return new OpenAIClient.Builder(getCredential(CredentialKey.OpenaiApiKey.INSTANCE))
        .setOrganization(OpenAISettings.getCurrentState().getOrganization())
        .build(getDefaultClientBuilder());
  }

  public static ClaudeClient getClaudeClient() {
    var builder = new ClaudeClient.Builder(getCredential(CredentialKey.AnthropicApiKey.INSTANCE),
            AnthropicSettings.getCurrentState().getApiVersion());
    if (AnthropicSettings.getCurrentState().hasCustomBaseHost()) {
      builder.setHost(AnthropicSettings.getCurrentState().getBaseHost());
    }
    return builder.build(getDefaultClientBuilder());
  }

  public static AzureClient getAzureClient() {
    var settings = AzureSettings.getCurrentState();
    var params = new AzureCompletionRequestParams(
        settings.getResourceName(),
        settings.getDeploymentId(),
        settings.getApiVersion());
    var useAzureActiveDirectoryAuthentication = settings.isUseAzureActiveDirectoryAuthentication();
    var credential = useAzureActiveDirectoryAuthentication
        ? getCredential(CredentialKey.AzureActiveDirectoryToken.INSTANCE)
        : getCredential(CredentialKey.AzureOpenaiApiKey.INSTANCE);
    return new AzureClient.Builder(credential, params)
        .setActiveDirectoryAuthentication(useAzureActiveDirectoryAuthentication)
        .build(getDefaultClientBuilder());
  }

  public static LlamaClient getLlamaClient() {
    var llamaSettings = LlamaSettings.getCurrentState();
    var builder = new LlamaClient.Builder()
        .setPort(llamaSettings.getServerPort());
    if (!llamaSettings.isRunLocalServer()) {
      builder.setHost(llamaSettings.getBaseHost());
      String apiKey = getCredential(CredentialKey.LlamaApiKey.INSTANCE);
      if (apiKey != null && !apiKey.isBlank()) {
        builder.setApiKey(apiKey);
      }
    }
    return builder.build(getDefaultClientBuilder());
  }

  public static OllamaClient getOllamaClient() {
    var host = ApplicationManager.getApplication()
        .getService(OllamaSettings.class)
        .getState()
        .getHost();
    var builder = new OllamaClient.Builder()
        .setHost(host);

    String apiKey = getCredential(CredentialKey.OllamaApikey.INSTANCE);
    if (apiKey != null && !apiKey.isBlank()) {
      builder.setApiKey(apiKey);
    }
    return builder.build(getDefaultClientBuilder());
  }

  public static GoogleClient getGoogleClient() {
    return new GoogleClient.Builder(getCredential(CredentialKey.GoogleApiKey.INSTANCE))
        .build(getDefaultClientBuilder());
  }

  public static OkHttpClient.Builder getDefaultClientBuilder() {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    var advancedSettings = AdvancedSettings.getCurrentState();
    var proxyHost = advancedSettings.getProxyHost();
    var proxyPort = advancedSettings.getProxyPort();
    if (!proxyHost.isEmpty() && proxyPort != 0) {
      builder.proxy(
          new Proxy(advancedSettings.getProxyType(), new InetSocketAddress(proxyHost, proxyPort)));
      if (advancedSettings.isProxyAuthSelected()) {
        builder.proxyAuthenticator((route, response) ->
            response.request()
                .newBuilder()
                .header("Proxy-Authorization", Credentials.basic(
                    advancedSettings.getProxyUsername(),
                    advancedSettings.getProxyPassword()))
                .build());
      }
    }

    return builder
        .connectTimeout(advancedSettings.getConnectTimeout(), TimeUnit.SECONDS)
        .readTimeout(advancedSettings.getReadTimeout(), TimeUnit.SECONDS);
  }
}
