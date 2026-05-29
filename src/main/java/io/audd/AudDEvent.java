package io.audd;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inspection event emitted by the SDK request lifecycle when an
 * {@link AudD.Builder#onEvent(java.util.function.Consumer)} hook is registered.
 * See design spec §7.7a.
 *
 * <p>Plain immutable data; never carries the {@code api_token} or request
 * body bytes — observability must not leak credentials. Hook exceptions are
 * swallowed by the SDK at FINE log level so observability never breaks the
 * request path.
 */
public final class AudDEvent {
    /** Phase of the request lifecycle. */
    public enum Kind {
        /** About to issue the network call. {@link #httpStatus()} / {@link #elapsedMs()} are null. */
        REQUEST,
        /** Server returned a response (2xx or otherwise). */
        RESPONSE,
        /** Local exception (connection error, decode failure, etc.). */
        EXCEPTION
    }

    private final Kind kind;
    private final String method;
    private final String url;
    private final String requestId;
    private final Integer httpStatus;
    private final Long elapsedMs;
    private final Integer errorCode;
    private final Map<String, Object> extras;

    private AudDEvent(Builder b) {
        this.kind = Objects.requireNonNull(b.kind, "kind is required");
        this.method = Objects.requireNonNull(b.method, "method is required");
        this.url = Objects.requireNonNull(b.url, "url is required");
        this.requestId = b.requestId;
        this.httpStatus = b.httpStatus;
        this.elapsedMs = b.elapsedMs;
        this.errorCode = b.errorCode;
        this.extras = b.extras == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
    }

    public Kind kind() { return kind; }
    /** AudD method name (e.g., {@code "recognize"}, {@code "addStream"}). */
    public String method() { return method; }
    /** Endpoint URL targeted by the request (no query params). */
    public String url() { return url; }
    /** {@code X-Request-Id} response header, if present. {@code null} for REQUEST kind. */
    public String requestId() { return requestId; }
    /** HTTP status from the response. {@code null} for REQUEST and EXCEPTION kinds. */
    public Integer httpStatus() { return httpStatus; }
    /** Wall-clock duration in milliseconds. {@code null} for REQUEST kind. */
    public Long elapsedMs() { return elapsedMs; }
    /** AudD {@code error_code}, if any. {@code null} otherwise. */
    public Integer errorCode() { return errorCode; }
    /** Free-form extra context; never contains credentials. Empty map is the default. */
    public Map<String, Object> extras() { return extras; }

    @Override
    public String toString() {
        return "AudDEvent{kind=" + kind + ", method=" + method + ", url=" + url
                + ", requestId=" + requestId + ", httpStatus=" + httpStatus
                + ", elapsedMs=" + elapsedMs + ", errorCode=" + errorCode
                + ", extras=" + extras + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Kind kind;
        private String method;
        private String url;
        private String requestId;
        private Integer httpStatus;
        private Long elapsedMs;
        private Integer errorCode;
        private Map<String, Object> extras;

        public Builder kind(Kind k) { this.kind = k; return this; }
        public Builder method(String m) { this.method = m; return this; }
        public Builder url(String u) { this.url = u; return this; }
        public Builder requestId(String r) { this.requestId = r; return this; }
        public Builder httpStatus(Integer s) { this.httpStatus = s; return this; }
        public Builder elapsedMs(Long ms) { this.elapsedMs = ms; return this; }
        public Builder errorCode(Integer c) { this.errorCode = c; return this; }
        public Builder extras(Map<String, Object> e) { this.extras = e; return this; }

        public AudDEvent build() { return new AudDEvent(this); }
    }
}
