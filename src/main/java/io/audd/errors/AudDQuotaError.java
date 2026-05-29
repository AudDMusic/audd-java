package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 902 — quota / per-copy limit reached. */
public final class AudDQuotaError extends AudDApiError {
    public AudDQuotaError(int errorCode, String message, int httpStatus, String requestId,
                          Map<String, Object> requestedParams, String requestMethod,
                          String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
