/**
 * AudD Java SDK — Java Platform Module System (JPMS) descriptor.
 *
 * <p>This module defines the public API surface of the AudD SDK. Modular
 * consumers can depend on it with a single {@code requires io.audd;}
 * directive in their own {@code module-info.java}.
 *
 * <h2>Exported packages</h2>
 * <ul>
 *   <li>{@link io.audd} — top-level synchronous and asynchronous clients
 *       ({@code AudD}, {@code AsyncAudD}) plus shared option types.</li>
 *   <li>{@link io.audd.streams} — Streams (radio monitoring) endpoints.</li>
 *   <li>{@link io.audd.customcatalog} — Custom-catalog management.</li>
 *   <li>{@link io.audd.advanced} — Lyrics search, Apple Music / Deezer / Napster lookups.</li>
 *   <li>{@link io.audd.errors} — Sealed exception hierarchy mapping every
 *       documented AudD error code.</li>
 *   <li>{@link io.audd.models} — Forward-compatible response DTOs.</li>
 * </ul>
 *
 * <p>The {@code io.audd.internal} package is intentionally <strong>not</strong>
 * exported. Consumers must not depend on its types; they have no compatibility
 * guarantee across releases.
 *
 * <h2>Transitive dependencies</h2>
 * <p>Jackson and OkHttp types appear in the public API (e.g. {@code JsonNode},
 * {@code OkHttpClient}, {@code RequestBody}), so the corresponding modules are
 * declared {@code transitive} — consumers requiring {@code io.audd} can use
 * them without re-declaring the dependency.
 *
 * @since 0.3.0
 */
module io.audd {
    // Public API surface.
    exports io.audd;
    exports io.audd.streams;
    exports io.audd.customcatalog;
    exports io.audd.advanced;
    exports io.audd.errors;
    exports io.audd.models;

    // Jackson types leak into the public API (JsonNode in error classes,
    // ObjectMapper accessor on AudD, @JsonProperty on DTOs) — re-export.
    requires transitive com.fasterxml.jackson.databind;
    requires transitive com.fasterxml.jackson.annotation;

    // OkHttpClient / RequestBody are part of the configuration surface
    // (AudDOptions.httpClient, body builders). okhttp3 is an automatic
    // module — its name is derived from the JAR filename.
    requires transitive okhttp3;

    // java.logging is required because the SDK uses java.util.logging
    // for the onEvent hook's swallow-and-warn fallback.
    requires java.logging;
}
