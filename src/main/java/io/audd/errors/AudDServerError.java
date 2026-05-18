package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 100 / 1000, unknown codes, generic upstream failures, and HTTP non-2xx
 * responses with non-JSON bodies (design spec §6.6).
 */
public final class AudDServerError extends AudDApiError {
    public AudDServerError(int errorCode, String message, int httpStatus, String requestId,
                           Map<String, Object> requestedParams, String requestMethod,
                           String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
