package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDSerializationError;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryPolicy;
import io.audd.models.CallbackEvent;
import io.audd.models.Stream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Sync streams namespace. */
public final class Streams {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int NO_CALLBACK_ERROR_CODE = 19;

    static final String PREFLIGHT_NO_CALLBACK_HINT =
        "Longpoll won't deliver events because no callback URL is configured for this account. "
        + "Set one first via streams.setCallbackUrl(...) — `https://audd.tech/empty/` is fine if "
        + "you only want longpolling and don't need a real receiver. "
        + "To skip this check, set skipCallbackCheck=true on LongpollOptions.";

    private final HttpClient http;
    private final RetryPolicy readPolicy;
    private final RetryPolicy mutatingPolicy;
    private final Supplier<String> apiTokenSupplier;
    private final Consumer<String> onDeprecation;
    private final String apiBase;

    public Streams(HttpClient http, RetryPolicy readPolicy, RetryPolicy mutatingPolicy,
                   String apiToken, Consumer<String> onDeprecation, String apiBase) {
        this(http, readPolicy, mutatingPolicy, () -> apiToken, onDeprecation, apiBase);
    }

    /**
     * Variant that takes a {@link Supplier} so the longpoll-category derivation
     * picks up the latest token after {@link io.audd.AudD#setApiToken(String)}.
     */
    public Streams(HttpClient http, RetryPolicy readPolicy, RetryPolicy mutatingPolicy,
                   Supplier<String> apiTokenSupplier, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.readPolicy = readPolicy;
        this.mutatingPolicy = mutatingPolicy;
        this.apiTokenSupplier = apiTokenSupplier;
        this.onDeprecation = onDeprecation;
        this.apiBase = apiBase;
    }

    /** Compute the 9-char longpoll category for this token + a radio_id. */
    public String deriveLongpollCategory(int radioId) {
        return CallbackHelpers.deriveLongpollCategory(apiTokenSupplier.get(), radioId);
    }

    /** Parse a callback POST body (already-parsed JSON tree) into a typed event. */
    public static CallbackEvent parseCallback(JsonNode body) {
        return CallbackHelpers.parseCallback(body);
    }

    /** Parse a callback POST body (raw bytes) into a typed event. */
    public static CallbackEvent parseCallback(byte[] body) {
        return CallbackHelpers.parseCallback(body);
    }

    /**
     * Parse a callback POST body from an {@link InputStream}. Reads to EOF;
     * does NOT close the stream. Use this from servlet/HTTP-handler code:
     *
     * <pre>{@code
     * try (InputStream in = httpRequest.getInputStream()) {
     *     CallbackEvent event = Streams.parseCallback(in);
     *     ...
     * }
     * }</pre>
     */
    public static CallbackEvent parseCallback(InputStream body) {
        return CallbackHelpers.parseCallback(body);
    }

    public static String deriveLongpollCategory(String apiToken, int radioId) {
        return CallbackHelpers.deriveLongpollCategory(apiToken, radioId);
    }

    public void setCallbackUrl(String url) {
        setCallbackUrl(url, null);
    }

    /**
     * Set the per-account callback URL.
     * If {@code returnMetadata} is non-null, it is appended as {@code ?return=...}
     * to the URL before sending. If the URL already has a {@code return} query
     * parameter, this throws — refusing to silently overwrite.
     */
    public void setCallbackUrl(String url, List<String> returnMetadata) {
        String mergedUrl = CallbackHelpers.addReturnToUrl(url, returnMetadata);
        Map<String, String> data = new HashMap<>();
        data.put("url", mergedUrl);
        post("setCallbackUrl", data, mutatingPolicy);
    }

    public String getCallbackUrl() {
        JsonNode body = post("getCallbackUrl", Collections.emptyMap(), readPolicy);
        return body.path("result").asText("");
    }

    public void add(AddStreamRequest req) {
        Map<String, String> data = new HashMap<>();
        data.put("url", req.url());
        data.put("radio_id", String.valueOf(req.radioId()));
        if (req.callbacks() != null) data.put("callbacks", req.callbacks());
        post("addStream", data, mutatingPolicy);
    }

    public void setUrl(int radioId, String url) {
        Map<String, String> data = new HashMap<>();
        data.put("radio_id", String.valueOf(radioId));
        data.put("url", url);
        post("setStreamUrl", data, mutatingPolicy);
    }

    public void delete(int radioId) {
        Map<String, String> data = new HashMap<>();
        data.put("radio_id", String.valueOf(radioId));
        post("deleteStream", data, mutatingPolicy);
    }

    public List<Stream> list() {
        JsonNode body = post("getStreams", Collections.emptyMap(), readPolicy);
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
    }

    /**
     * Long-poll for events on a category. Returns a {@link LongpollPoll}
     * which the caller drives by registering callbacks and calling
     * {@link LongpollPoll#run()} (or {@link LongpollPoll#runAsync()}).
     *
     * <p>By default performs a one-time preflight {@link #getCallbackUrl()}
     * call before subscribing — if no callback URL is configured for the
     * account, the longpoll endpoint silently returns "no events" forever.
     * To skip the preflight, set {@link LongpollOptions#skipCallbackCheck()}
     * to true.</p>
     */
    public LongpollPoll longpoll(String category) {
        return longpoll(category, LongpollOptions.defaults());
    }

    public LongpollPoll longpoll(String category, LongpollOptions opts) {
        if (!opts.skipCallbackCheck()) {
            try {
                getCallbackUrl();
            } catch (AudDApiError exc) {
                if (exc.errorCode() == NO_CALLBACK_ERROR_CODE) {
                    throw new AudDInvalidRequestError(
                        0, PREFLIGHT_NO_CALLBACK_HINT, exc.httpStatus(), exc.requestId(),
                        Collections.emptyMap(), null, null, null);
                }
                throw exc;
            }
        }
        return new LongpollPoll(http, readPolicy, apiBase, category, opts);
    }

    private JsonNode post(String path, Map<String, String> data, RetryPolicy policy) {
        HttpResponse resp;
        try {
            resp = policy.runSync(() -> http.postForm(apiBase + "/" + path + "/", data, null));
        } catch (IOException e) {
            throw new AudDConnectionError(e.getMessage() == null ? "connection error" : e.getMessage(), e);
        }
        return ResponseDecoder.decodeOrRaise(resp, onDeprecation, false);
    }
}
