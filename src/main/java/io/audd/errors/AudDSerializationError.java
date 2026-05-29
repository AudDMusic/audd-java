package io.audd.errors;

/**
 * Server returned a 2xx HTTP response with a body that wasn't valid JSON.
 * Reserved for the 2xx case — non-2xx with non-JSON bodies surface as
 * {@link AudDServerError} preserving the HTTP status (design spec §6.6).
 */
public final class AudDSerializationError extends AudDException {
    private final String rawText;

    public AudDSerializationError(String message, String rawText) {
        super(message);
        this.rawText = rawText == null ? "" : rawText;
    }

    public AudDSerializationError(String message) { this(message, ""); }

    public String rawText() { return rawText; }
}
