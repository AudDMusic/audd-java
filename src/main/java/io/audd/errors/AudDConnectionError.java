package io.audd.errors;

/** Network / TLS / timeout — no response received. */
public final class AudDConnectionError extends AudDException {
    public AudDConnectionError(String message) { super(message); }
    public AudDConnectionError(String message, Throwable cause) { super(message, cause); }
}
