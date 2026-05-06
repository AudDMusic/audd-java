package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 50 / 51 / 600/601/602 / 700/701/702 / 906 — bad input from caller. */
public final class AudDInvalidRequestError extends AudDApiError {
    public AudDInvalidRequestError(int errorCode, String message, int httpStatus, String requestId,
                                   Map<String, Object> requestedParams, String requestMethod,
                                   String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
