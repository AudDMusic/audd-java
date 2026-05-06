package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 904 / 905 — endpoint not available with this token (subscription /
 * permission). The {@link AudDCustomCatalogAccessError} subclass overrides
 * the message specifically for the {@code custom_catalog} surface.
 */
public class AudDSubscriptionError extends AudDApiError {
    public AudDSubscriptionError(int errorCode, String message, int httpStatus, String requestId,
                                 Map<String, Object> requestedParams, String requestMethod,
                                 String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
