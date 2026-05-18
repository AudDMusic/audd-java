package io.audd.advanced;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDSerializationError;
import io.audd.errors.ErrorMapping;
import io.audd.internal.HttpClient;
import io.audd.internal.RetryPolicy;
import io.audd.models.LyricsResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Async advanced namespace — mirror of {@link Advanced}. */
public final class AsyncAdvanced {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final RetryPolicy recognitionPolicy;
    private final Consumer<String> onDeprecation;
    private final String apiBase;

    public AsyncAdvanced(HttpClient http, RetryPolicy recognitionPolicy, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.recognitionPolicy = recognitionPolicy;
        this.onDeprecation = onDeprecation;
        this.apiBase = apiBase;
    }

    public CompletableFuture<List<LyricsResult>> findLyrics(String query) {
        Map<String, String> params = new HashMap<>();
        params.put("q", query);
        return rawRequest("findLyrics", params).thenApply(body -> {
            if ("error".equals(body.path("status").asText(""))) {
                AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
                throw exc;
            }
            JsonNode result = body.path("result");
            List<LyricsResult> out = new ArrayList<>();
            if (result == null || !result.isArray()) return out;
            for (JsonNode el : result) {
                try {
                    LyricsResult lr = MAPPER.treeToValue(el, LyricsResult.class);
                    if (lr != null) lr.setRawResponse(el);
                    out.add(lr);
                } catch (Exception e) {
                    throw new AudDSerializationError("Failed to decode LyricsResult");
                }
            }
            return out;
        });
    }

    public CompletableFuture<JsonNode> rawRequest(String method, Map<String, String> params) {
        Map<String, String> finalParams = new HashMap<>(params == null ? Collections.emptyMap() : params);
        return recognitionPolicy.runAsync(() -> http.postFormAsync(apiBase + "/" + method + "/", finalParams, null))
                .thenApply(resp -> {
                    JsonNode body = resp.jsonBody();
                    if (body == null || !body.isObject()) {
                        throw new AudDSerializationError("Unparseable response", resp.rawText());
                    }
                    return body;
                });
    }
}
