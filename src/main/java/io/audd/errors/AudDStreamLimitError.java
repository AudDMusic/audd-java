package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 610 — subscription stream slots exhausted. */
public final class AudDStreamLimitError extends AudDApiError {
    public AudDStreamLimitError(int errorCode, String message, int httpStatus, String requestId,
                                Map<String, Object> requestedParams, String requestMethod,
                                String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
