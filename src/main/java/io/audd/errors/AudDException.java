package io.audd.errors;

/**
 * Base for everything raised by this SDK. Catch {@code AudDException} to
 * catch them all.
 *
 * <p>Closed hierarchy in practice (Java 11 compatibility):
 * <ul>
 *   <li>{@link AudDApiError} — server returned status=error</li>
 *   <li>{@link AudDConnectionError} — network/TLS/timeout, no response</li>
 *   <li>{@link AudDSerializationError} — server returned malformed JSON on a 2xx</li>
 * </ul>
 */
public abstract class AudDException extends RuntimeException {

    public AudDException(String message) { super(message); }
    public AudDException(String message, Throwable cause) { super(message, cause); }
}
