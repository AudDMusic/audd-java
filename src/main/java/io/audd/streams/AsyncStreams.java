package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDSerializationError;
import io.audd.internal.HttpClient;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryPolicy;
import io.audd.models.Stream;
import io.audd.models.StreamCallbackPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Async streams namespace. Returns {@link CompletableFuture}s. */
public final class AsyncStreams {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int NO_CALLBACK_ERROR_CODE = 19;

    private final HttpClient http;
    private final RetryPolicy readPolicy;
    private final RetryPolicy mutatingPolicy;
    private final Supplier<String> apiTokenSupplier;
    private final Consumer<String> onDeprecation;
    private final String apiBase;

    public AsyncStreams(HttpClient http, RetryPolicy readPolicy, RetryPolicy mutatingPolicy,
                        String apiToken, Consumer<String> onDeprecation, String apiBase) {
        this(http, readPolicy, mutatingPolicy, () -> apiToken, onDeprecation, apiBase);
    }

    /** Variant that takes a {@link Supplier} so the longpoll-category derivation
     * picks up the latest token after {@link io.audd.AsyncAudD#setApiToken(String)}. */
    public AsyncStreams(HttpClient http, RetryPolicy readPolicy, RetryPolicy mutatingPolicy,
                        Supplier<String> apiTokenSupplier, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.readPolicy = readPolicy;
        this.mutatingPolicy = mutatingPolicy;
        this.apiTokenSupplier = apiTokenSupplier;
        this.onDeprecation = onDeprecation;
        this.apiBase = apiBase;
    }

    public String deriveLongpollCategory(int radioId) {
        return CallbackHelpers.deriveLongpollCategory(apiTokenSupplier.get(), radioId);
    }

    public static StreamCallbackPayload parseCallback(JsonNode body) {
        return CallbackHelpers.parseCallback(body);
    }

    public CompletableFuture<Void> setCallbackUrl(String url) {
        return setCallbackUrl(url, null);
    }

    public CompletableFuture<Void> setCallbackUrl(String url, List<String> returnMetadata) {
        String merged = CallbackHelpers.addReturnToUrl(url, returnMetadata);
        Map<String, String> data = new HashMap<>();
        data.put("url", merged);
        return post("setCallbackUrl", data, mutatingPolicy).thenApply(b -> null);
    }

    public CompletableFuture<String> getCallbackUrl() {
        return post("getCallbackUrl", Collections.emptyMap(), readPolicy)
                .thenApply(body -> body.path("result").asText(""));
    }

    public CompletableFuture<Void> add(AddStreamRequest req) {
        Map<String, String> data = new HashMap<>();
        data.put("url", req.url());
        data.put("radio_id", String.valueOf(req.radioId()));
        if (req.callbacks() != null) data.put("callbacks", req.callbacks());
        return post("addStream", data, mutatingPolicy).thenApply(b -> null);
    }

    public CompletableFuture<Void> setUrl(int radioId, String url) {
        Map<String, String> data = new HashMap<>();
        data.put("radio_id", String.valueOf(radioId));
        data.put("url", url);
        return post("setStreamUrl", data, mutatingPolicy).thenApply(b -> null);
    }

    public CompletableFuture<Void> delete(int radioId) {
        Map<String, String> data = new HashMap<>();
        data.put("radio_id", String.valueOf(radioId));
        return post("deleteStream", data, mutatingPolicy).thenApply(b -> null);
    }

    public CompletableFuture<List<Stream>> list() {
        return post("getStreams", Collections.emptyMap(), readPolicy).thenApply(body -> {
            JsonNode arr = body.path("result");
            List<Stream> out = new ArrayList<>();
            if (arr == null || !arr.isArray()) return out;
            for (JsonNode el : arr) {
                try {
                    Stream s = MAPPER.treeToValue(el, Stream.class);
                    if (s != null) s.setRawResponse(el);
                    out.add(s);
                } catch (Exception e) {
                    throw new AudDSerializationError("Failed to decode Stream", el == null ? "" : el.toString());
                }
            }
            return out;
        });
    }

    /**
     * Async preflight + one-shot fetch of the next longpoll event. Callers
     * that want to consume a stream of events should call this in a loop,
     * passing the previous event's timestamp as {@link LongpollOptions#sinceTime}.
     */
    public CompletableFuture<JsonNode> longpollOnce(String category, LongpollOptions opts) {
        CompletableFuture<Void> preflight;
        if (!opts.skipCallbackCheck()) {
            preflight = getCallbackUrl().handle((u, exc) -> {
                if (exc == null) return null;
                Throwable cause = unwrap(exc);
                if (cause instanceof AudDApiError && ((AudDApiError) cause).errorCode() == NO_CALLBACK_ERROR_CODE) {
                    AudDApiError api = (AudDApiError) cause;
                    throw new AudDInvalidRequestError(0, Streams.PREFLIGHT_NO_CALLBACK_HINT,
                            api.httpStatus(), api.requestId(),
                            Collections.emptyMap(), null, null, null);
                }
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause);
            });
        } else {
            preflight = CompletableFuture.completedFuture(null);
        }
        return preflight.thenCompose(_v -> {
            Map<String, String> params = new HashMap<>();
            params.put("category", category);
            params.put("timeout", String.valueOf(opts.timeout()));
            if (opts.sinceTime() != null) params.put("since_time", String.valueOf(opts.sinceTime()));
            return readPolicy.runAsync(() -> http.getAsync(apiBase + "/longpoll/", params))
                    .thenApply(resp -> {
                        JsonNode body = resp.jsonBody();
                        if (body == null || !body.isObject()) {
                            throw new AudDSerializationError("Unparseable longpoll response", resp.rawText());
                        }
                        return body;
                    });
        });
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }

    private CompletableFuture<JsonNode> post(String path, Map<String, String> data, RetryPolicy policy) {
        return policy.runAsync(() -> http.postFormAsync(apiBase + "/" + path + "/", data, null))
                .thenApply(resp -> ResponseDecoder.decodeOrRaise(resp, onDeprecation, false));
    }
}
