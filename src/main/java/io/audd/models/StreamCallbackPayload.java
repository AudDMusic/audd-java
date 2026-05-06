package io.audd.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A wrapper over a callback POST body — either a recognition result or a
 * notification. {@link #isResult()} / {@link #isNotification()} disambiguate;
 * accessors return {@code null} for the wrong-shape variant.
 */
public final class StreamCallbackPayload {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StreamCallbackResult result;
    private final StreamCallbackNotification notification;
    private final Long time;
    private final JsonNode rawPayload;

    private StreamCallbackPayload(StreamCallbackResult result, StreamCallbackNotification notification,
                                  Long time, JsonNode rawPayload) {
        this.result = result;
        this.notification = notification;
        this.time = time;
        this.rawPayload = rawPayload;
    }

    public StreamCallbackResult result() { return result; }
    public StreamCallbackNotification notification() { return notification; }
    public Long time() { return time; }
    public JsonNode rawPayload() { return rawPayload; }

    public boolean isResult() { return result != null; }
    public boolean isNotification() { return notification != null; }

    /** Parse a callback POST body into a typed payload. */
    public static StreamCallbackPayload parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("callback body must be a JSON object");
        }
        if (body.has("notification")) {
            StreamCallbackNotification n = MAPPER.convertValue(body.get("notification"), StreamCallbackNotification.class);
            if (n != null) n.setRawResponse(body.get("notification"));
            Long t = body.has("time") && body.get("time").isNumber() ? body.get("time").asLong() : null;
            return new StreamCallbackPayload(null, n, t, body);
        }
        JsonNode inner = body.has("result") ? body.get("result") : body;
        StreamCallbackResult r = MAPPER.convertValue(inner, StreamCallbackResult.class);
        if (r != null) r.setRawResponse(inner);
        return new StreamCallbackPayload(r, null, null, body);
    }
}
