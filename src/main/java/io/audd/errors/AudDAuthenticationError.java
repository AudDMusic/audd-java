package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 900 / 901 / 903 — token is the problem. */
public final class AudDAuthenticationError extends AudDApiError {
    public AudDAuthenticationError(int errorCode, String message, int httpStatus, String requestId,
                                   Map<String, Object> requestedParams, String requestMethod,
                                   String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
