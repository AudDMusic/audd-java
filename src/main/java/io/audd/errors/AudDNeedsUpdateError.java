package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 20 — app needs update / paid version required. */
public final class AudDNeedsUpdateError extends AudDApiError {
    public AudDNeedsUpdateError(int errorCode, String message, int httpStatus, String requestId,
                                Map<String, Object> requestedParams, String requestMethod,
                                String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
