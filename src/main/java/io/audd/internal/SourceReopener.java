package io.audd.internal;

import okhttp3.RequestBody;

import java.io.IOException;

/**
 * Yields a fresh {@link RequestBody} on each invocation so retries don't
 * read from an exhausted source. URL-source sentinels return {@code null}
 * from {@link #open()} — the caller passes the URL via the {@code url=...}
 * form field instead.
 *
 * <p>See design spec §7.1 "retry-safe source handling" — every SDK
 * implements {@code prepare_source} as a re-opener that yields fresh
 * request bodies on each retry attempt.</p>
 */
@FunctionalInterface
public interface SourceReopener {
    /**
     * Returns a fresh request body for one attempt. Returning {@code null}
     * indicates "this isn't a body source" (i.e. URL source — see
     * {@link SourcePreparer#urlOf(SourceReopener)}).
     */
    RequestBody open() throws IOException;
}
