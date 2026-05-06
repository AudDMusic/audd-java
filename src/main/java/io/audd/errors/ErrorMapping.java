package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Maps an AudD numeric error code to its exception class and constructs the
 * exception from a parsed error response body. Unknown codes fall back to
 * {@link AudDServerError}.
 */
public final class ErrorMapping {
    private ErrorMapping() {}

    public static Class<? extends AudDApiError> errorForCode(int code) {
        switch (code) {
            case 900: case 901: case 903: return AudDAuthenticationError.class;
            case 902: return AudDQuotaError.class;
            case 904: case 905: return AudDSubscriptionError.class;
            case 50: case 51: case 600: case 601: case 602:
            case 700: case 701: case 702: case 906: return AudDInvalidRequestError.class;
            case 300: case 400: case 500: return AudDInvalidAudioError.class;
            case 610: return AudDStreamLimitError.class;
            case 611: return AudDRateLimitError.class;
            case 907: return AudDNotReleasedError.class;
            case 19: case 31337: return AudDBlockedError.class;
            case 20: return AudDNeedsUpdateError.class;
            case 100: case 1000: return AudDServerError.class;
            default: return AudDServerError.class;
        }
    }

    /**
     * Inspect a parsed error response body, build the appropriate exception,
     * and return it for the caller to throw.
     *
     * @param body parsed JSON body of an AudD response (status=error)
     * @param httpStatus HTTP status code from the original response
     * @param requestId X-Request-Id header value, or null
     * @param customCatalogContext if true, 904/905 are upgraded to {@link AudDCustomCatalogAccessError}
     */
    public static AudDApiError buildFromErrorBody(JsonNode body, int httpStatus, String requestId,
                                                  boolean customCatalogContext) {
        JsonNode err = body.path("error");
        int code = err.path("error_code").asInt(0);
        String message = err.path("error_message").asText("");

        JsonNode params = body.path("request_params");
        if (params.isMissingNode() || params.isNull()) params = body.path("requested_params");
        Map<String, Object> requestedParams = nodeToMap(params);

        String requestMethod = body.path("request_api_method").isMissingNode()
                ? null : body.path("request_api_method").asText(null);
        String branded = brandedMessage(body.path("result"));

        Class<? extends AudDApiError> cls = errorForCode(code);
        if (customCatalogContext && cls == AudDSubscriptionError.class) {
            return new AudDCustomCatalogAccessError(code, message, httpStatus, requestId,
                    requestedParams, requestMethod, branded, body);
        }
        return construct(cls, code, message, httpStatus, requestId, requestedParams, requestMethod, branded, body);
    }

    private static AudDApiError construct(Class<? extends AudDApiError> cls,
                                          int code, String message, int httpStatus, String requestId,
                                          Map<String, Object> params, String requestMethod,
                                          String branded, JsonNode rawResponse) {
        if (cls == AudDAuthenticationError.class) return new AudDAuthenticationError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDQuotaError.class) return new AudDQuotaError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDSubscriptionError.class) return new AudDSubscriptionError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDInvalidRequestError.class) return new AudDInvalidRequestError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDInvalidAudioError.class) return new AudDInvalidAudioError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDRateLimitError.class) return new AudDRateLimitError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDStreamLimitError.class) return new AudDStreamLimitError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDNotReleasedError.class) return new AudDNotReleasedError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDBlockedError.class) return new AudDBlockedError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        if (cls == AudDNeedsUpdateError.class) return new AudDNeedsUpdateError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
        return new AudDServerError(code, message, httpStatus, requestId, params, requestMethod, branded, rawResponse);
    }

    private static Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, Object> out = new HashMap<>();
        ObjectNode obj = (ObjectNode) node;
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isTextual()) out.put(e.getKey(), v.asText());
            else if (v.isInt()) out.put(e.getKey(), v.asInt());
            else if (v.isLong()) out.put(e.getKey(), v.asLong());
            else if (v.isDouble() || v.isFloat()) out.put(e.getKey(), v.asDouble());
            else if (v.isBoolean()) out.put(e.getKey(), v.asBoolean());
            else out.put(e.getKey(), v.toString());
        }
        return out;
    }

    private static String brandedMessage(JsonNode result) {
        if (result == null || !result.isObject()) return null;
        String artist = result.path("artist").isTextual() ? result.path("artist").asText() : null;
        String title = result.path("title").isTextual() ? result.path("title").asText() : null;
        if ((artist == null || artist.isEmpty()) && (title == null || title.isEmpty())) {
            return null;
        }
        if (artist != null && !artist.isEmpty() && title != null && !title.isEmpty()) {
            return artist + " — " + title;
        }
        return artist != null && !artist.isEmpty() ? artist : title;
    }
}
