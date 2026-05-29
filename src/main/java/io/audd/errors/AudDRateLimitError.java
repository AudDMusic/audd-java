package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 611 — per-stream daily rate limit (and HTTP 429). */
public final class AudDRateLimitError extends AudDApiError {
    public AudDRateLimitError(int errorCode, String message, int httpStatus, String requestId,
                              Map<String, Object> requestedParams, String requestMethod,
                              String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
