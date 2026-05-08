package io.audd.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wraps a parsed HTTP response. {@code jsonBody} is the parsed JSON tree, or
 * {@code null} if the body did not parse as JSON. {@code rawText} preserves
 * the original response body for diagnostic purposes (e.g. when the body
 * isn't valid JSON).
 */
public final class HttpResponse {
    private final JsonNode jsonBody;
    private final int httpStatus;
    private final String requestId;
    private final String rawText;

    public HttpResponse(JsonNode jsonBody, int httpStatus, String requestId, String rawText) {
        this.jsonBody = jsonBody;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.rawText = rawText == null ? "" : rawText;
    }

    public JsonNode jsonBody() { return jsonBody; }
    public int httpStatus() { return httpStatus; }
    public String requestId() { return requestId; }
    public String rawText() { return rawText; }
}
