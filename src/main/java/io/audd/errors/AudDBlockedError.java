package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 19 family + 31337 — security/abuse/sanctions/IP ban/maintenance. */
public final class AudDBlockedError extends AudDApiError {
    public AudDBlockedError(int errorCode, String message, int httpStatus, String requestId,
                            Map<String, Object> requestedParams, String requestMethod,
                            String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
