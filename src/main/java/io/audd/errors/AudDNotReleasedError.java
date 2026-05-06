package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 907 — song hasn't been released yet. */
public final class AudDNotReleasedError extends AudDApiError {
    public AudDNotReleasedError(int errorCode, String message, int httpStatus, String requestId,
                                Map<String, Object> requestedParams, String requestMethod,
                                String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
