package com.stevencastro.wornask;

import android.util.Base64;
import android.util.Log;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends one question (camera photo + spoken text) to Claude and streams the
 * answer back. Blocking; call it off the main thread.
 */
final class ClaudeClient {
    interface Callback {
        /** A chunk of answer text, in order. */
        void onText(String delta);

        /** Finished. {@code error} is a short message for the display, or null. */
        void onDone(String error);
    }

    private static final String TAG = "WornAsk";
    private static final String MODEL = "claude-opus-5-5";
    // Low effort keeps answers quick on the glasses; raise it for harder questions.
    private static final BetaOutputConfig.Effort EFFORT = BetaOutputConfig.Effort.LOW;
    private static final long MAX_TOKENS = 4000;
    // On a refusal, the API retries on a fallback model it picks by category.
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final String SYSTEM =
            "You're answering through smart glasses with a small 640x480 display. "
                    + "The image is what the wearer is looking at right now. "
                    + "Answer in at most 3 short sentences, plain text, no markdown.";

    private final AnthropicClient client;

    ClaudeClient(String apiKey) {
        client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    void ask(byte[] jpeg, String question, Callback callback) {
        List<BetaContentBlockParam> content = new ArrayList<>();
        if (jpeg != null) {
            content.add(BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                    .source(BetaBase64ImageSource.builder()
                            .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            .build())
                    .build()));
        }
        content.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder()
                .text(question)
                .build()));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM)
                .outputConfig(BetaOutputConfig.builder().effort(EFFORT).build())
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
                .addUserMessageOfContentBlockParams(content)
                .build();

        boolean[] gotText = {false};
        BetaStopReason[] stop = {null};
        try (StreamResponse<BetaRawMessageStreamEvent> stream =
                     client.beta().messages().createStreaming(params)) {
            stream.stream().forEach(event -> {
                event.contentBlockDelta().ifPresent(e ->
                        e.delta().text().ifPresent(t -> {
                            gotText[0] = true;
                            callback.onText(t.text());
                        }));
                event.messageDelta().ifPresent(e ->
                        e.delta().stopReason().ifPresent(r -> stop[0] = r));
            });
        } catch (UnauthorizedException | PermissionDeniedException e) {
            callback.onDone("API key rejected");
            return;
        } catch (RateLimitException e) {
            callback.onDone("Rate limited, try again");
            return;
        } catch (InternalServerException e) {
            callback.onDone("Claude is overloaded, try again");
            return;
        } catch (AnthropicServiceException e) {
            Log.w(TAG, "API error", e);
            callback.onDone("API error " + e.statusCode());
            return;
        } catch (AnthropicIoException e) {
            Log.w(TAG, "network error", e);
            callback.onDone("Network error");
            return;
        } catch (RuntimeException e) {
            Log.e(TAG, "request failed", e);
            callback.onDone("Error: " + e.getClass().getSimpleName());
            return;
        }

        if (BetaStopReason.REFUSAL.equals(stop[0])) {
            callback.onDone("Claude declined to answer");
        } else if (!gotText[0]) {
            callback.onDone("No answer");
        } else {
            callback.onDone(null);
        }
    }
}
