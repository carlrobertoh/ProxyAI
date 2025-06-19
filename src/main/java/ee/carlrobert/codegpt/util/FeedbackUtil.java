package ee.carlrobert.codegpt.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.completions.ChatCompletionParameters;
import ee.carlrobert.codegpt.completions.CompletionClientProvider;
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;


public class FeedbackUtil {
    private final static Logger LOG = Logger.getInstance(FeedbackUtil.class.getName());

    public static void likeResponse(ChatCompletionParameters params) {
        sendFeedback(params, true);
    }

    public static void dislikeResponse(ChatCompletionParameters params) {
        sendFeedback(params, false);
    }

    private static void sendFeedback(ChatCompletionParameters params, boolean liked) {
        var requestId = params.getRequestId();
        var url = ApplicationManager.getApplication().getService(CustomServicesSettings.class)
                .getState().getActive().getChatCompletionSettings().getUrl();
        assert url != null;
        var feedbackUrl = url.split("/chat")[0] + String.format("/requests/chat/%s/feedback", requestId);

        OkHttpClient client = CompletionClientProvider.getDefaultClientBuilder().build();
        var response = client.newCall(
                new Request.Builder()
                        .url(HttpUrl.get(feedbackUrl))
                        .method("POST", RequestBody.create(
                                String.format("{\"liked\": %b}", liked),
                                MediaType.get("application/json")))
                        .build()
        );
        try (var result = response.execute()) {
            LOG.info("Feedback request sent for requestId: " + requestId);
        } catch (IOException e) {
            LOG.warn("Failed to send feedback for requestId: " + requestId, e);
        }
    }
}
