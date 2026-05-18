package io.audd.advanced;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDSerializationError;
import io.audd.errors.ErrorMapping;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.RetryPolicy;
import io.audd.models.LyricsResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sync advanced namespace — lyrics search + raw escape hatch.
 * Reach this only via {@code audd.advanced()} — deliberately not on the
 * main client.
 */
public final class Advanced {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final RetryPolicy recognitionPolicy;
    private final Consumer<String> onDeprecation;
    private final String apiBase;

    public Advanced(HttpClient http, RetryPolicy recognitionPolicy, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.recognitionPolicy = recognitionPolicy;
        this.onDeprecation = onDeprecation;
        this.apiBase = apiBase;
    }

    public List<LyricsResult> findLyrics(String query) {
        Map<String, String> params = new HashMap<>();
        params.put("q", query);
        JsonNode body = rawRequest("findLyrics", params);
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
    }

    /**
     * Hit any AudD endpoint by method name and return the raw JSON body.
     * Useful for endpoints not yet wrapped by typed methods on this SDK.
     */
    public JsonNode rawRequest(String method, Map<String, String> params) {
        if (params == null) params = Collections.emptyMap();
        HttpResponse resp;
        Map<String, String> finalParams = new HashMap<>(params);
        try {
            resp = recognitionPolicy.runSync(() -> http.postForm(apiBase + "/" + method + "/", finalParams, null));
        } catch (IOException e) {
            throw new AudDConnectionError(e.getMessage() == null ? "connection error" : e.getMessage(), e);
        }
        JsonNode body = resp.jsonBody();
        if (body == null || !body.isObject()) {
            throw new AudDSerializationError("Unparseable response", resp.rawText());
        }
        return body;
    }
}
