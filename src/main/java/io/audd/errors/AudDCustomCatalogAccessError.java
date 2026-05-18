package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 904 raised specifically from the custom-catalog surface. Overrides the
 * user-facing message to clarify intent (custom catalog is NOT for
 * recognition; use recognize / recognizeEnterprise instead).
 */
public final class AudDCustomCatalogAccessError extends AudDSubscriptionError {

    private static final String OVERRIDE_TEMPLATE =
        "Adding songs to your custom catalog requires enterprise access that isn't "
        + "enabled on your account.\n\n"
        + "Note: the custom-catalog endpoint is for adding songs to your private "
        + "fingerprint database, not for music recognition. If you intended to "
        + "identify music, use recognize(...) (or recognizeEnterprise(...) for "
        + "files longer than 25 seconds) instead.\n\n"
        + "To request custom-catalog access, contact api@audd.io.\n\n"
        + "[Server message: %s]";

    public AudDCustomCatalogAccessError(int errorCode, String serverMessage, int httpStatus, String requestId,
                                        Map<String, Object> requestedParams, String requestMethod,
                                        String brandedMessage, JsonNode rawResponse) {
        super(errorCode, String.format(OVERRIDE_TEMPLATE, serverMessage == null ? "" : serverMessage),
              httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse);
    }
}
