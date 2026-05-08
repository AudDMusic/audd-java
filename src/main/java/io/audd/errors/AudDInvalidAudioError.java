package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** 300 / 400 / 500 — caller's audio file is the problem. */
public final class AudDInvalidAudioError extends AudDApiError {
    public AudDInvalidAudioError(int errorCode, String message, int httpStatus, String requestId,
                                 Map<String, Object> requestedParams, String requestMethod,
                                 String brandedMessage, JsonNode rawResponse) {
        super(errorCode, message, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
