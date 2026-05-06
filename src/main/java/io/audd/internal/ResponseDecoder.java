package io.audd.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDSerializationError;
import io.audd.errors.AudDServerError;
import io.audd.errors.ErrorMapping;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * Inspect an {@link HttpResponse}, raise typed errors for obvious failures,
 * else return the parsed JSON body.
 *
 * <p>Mirrors {@code audd-python/src/audd/client.py:_decode_or_raise}:
 * <ul>
 *   <li>non-2xx HTTP with non-JSON body → {@code AudDServerError} preserving status (S2)</li>
 *   <li>2xx with non-JSON body → {@code AudDSerializationError}</li>
 *   <li>{@code status=error} with code-51 + result → emit deprecation warning, strip error, fall through (C3)</li>
 *   <li>{@code status=error} otherwise → raise typed exception</li>
 *   <li>{@code status=success} → return body</li>
 * </ul>
 */
public final class ResponseDecoder {
    public static final int DEPRECATED_PARAMS_CODE = 51;
    public static final int HTTP_CLIENT_ERROR_FLOOR = 400;

    private ResponseDecoder() {}

    /**
     * Decode a response that may need code-51 deprecation handling.
     *
     * @param resp the wrapped HTTP response
     * @param onDeprecation callback invoked with the deprecation message when
     *                      code 51 is seen with a usable result. May be null;
     *                      a default warning is logged.
     * @param customCatalogContext if true, 904 / 905 are upgraded to AudDCustomCatalogAccessError
     */
    public static JsonNode decodeOrRaise(HttpResponse resp, Consumer<String> onDeprecation,
                                         boolean customCatalogContext) {
        JsonNode body = resp.jsonBody();
        if (body == null || !body.isObject()) {
            // S2: non-2xx with non-JSON → AudDServerError preserving status.
            // 2xx with non-JSON → AudDSerializationError.
            if (resp.httpStatus() >= HTTP_CLIENT_ERROR_FLOOR) {
                throw new AudDServerError(0,
                    "HTTP " + resp.httpStatus() + " with non-JSON response body",
                    resp.httpStatus(), resp.requestId(),
                    Collections.emptyMap(), null, null, null);
            }
            throw new AudDSerializationError("Unparseable response", resp.rawText());
        }

        // C3: code 51 + a usable `result` → warn and strip, fall through to success.
        body = maybeStripCode51(body, onDeprecation);

        String status = body.path("status").asText("");
        if ("error".equals(status)) {
            AudDApiError exc = ErrorMapping.buildFromErrorBody(body, resp.httpStatus(), resp.requestId(), customCatalogContext);
            throw exc;
        }
        if ("success".equals(status)) {
            return body;
        }
        // Some endpoints (callbacks) use status="-"; we accept that too for callback bodies.
        // For the main client, anything else is unexpected.
        throw new AudDServerError(0,
            "Unexpected response status: \"" + status + "\"",
            resp.httpStatus(), resp.requestId(),
            Collections.emptyMap(), null, null, body);
    }

    /**
     * If the response body has a code-51 error AND a usable result, strip the
     * error, set status=success, and emit the deprecation message to the
     * provided consumer (or a default Logger.warning if null).
     *
     * <p>If no result is present, leave the body unchanged so the caller's
     * normal error path raises {@link io.audd.errors.AudDInvalidRequestError}.
     */
    static JsonNode maybeStripCode51(JsonNode body, Consumer<String> onDeprecation) {
        if (!body.isObject()) return body;
        JsonNode err = body.path("error");
        int code = err.path("error_code").asInt(0);
        if (code != DEPRECATED_PARAMS_CODE) return body;
        if (body.path("result").isMissingNode() || body.path("result").isNull()) {
            // No usable result — let the normal error path raise.
            return body;
        }
        String message = err.path("error_message").asText("Deprecated parameter used");
        if (onDeprecation != null) {
            onDeprecation.accept(message);
        } else {
            java.util.logging.Logger.getLogger("io.audd").warning(message);
        }
        com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy();
        obj.remove("error");
        obj.put("status", "success");
        return obj;
    }
}
