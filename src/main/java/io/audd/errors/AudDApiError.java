package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Map;

/**
 * Server returned {@code status=error}. Carries the AudD numeric error
 * code, human-readable message, HTTP status, request ID, the redacted
 * request param echo, and the raw response.
 *
 * <p>Sealed: catch the subclasses for typed handling, or this base for
 * "any AudD API error".</p>
 */
public abstract class AudDApiError extends AudDException {

    private final int errorCode;
    private final String serverMessage;
    private final int httpStatus;
    private final String requestId;
    private final Map<String, Object> requestedParams;
    private final String requestMethod;
    private final String brandedMessage;
    private final JsonNode rawResponse;

    public AudDApiError(
            int errorCode,
            String message,
            int httpStatus,
            String requestId,
            Map<String, Object> requestedParams,
            String requestMethod,
            String brandedMessage,
            JsonNode rawResponse) {
        super("[#" + errorCode + "] " + (message == null ? "" : message));
        this.errorCode = errorCode;
        this.serverMessage = message == null ? "" : message;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.requestedParams = requestedParams == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(requestedParams);
        this.requestMethod = requestMethod;
        this.brandedMessage = brandedMessage;
        this.rawResponse = rawResponse;
    }

    public int errorCode() { return errorCode; }
    public String serverMessage() { return serverMessage; }
    public int httpStatus() { return httpStatus; }
    public String requestId() { return requestId; }
    public Map<String, Object> requestedParams() { return requestedParams; }
    public String requestMethod() { return requestMethod; }
    public String brandedMessage() { return brandedMessage; }
    public JsonNode rawResponse() { return rawResponse; }
}
