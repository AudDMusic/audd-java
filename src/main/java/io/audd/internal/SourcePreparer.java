package io.audd.internal;

import okhttp3.MediaType;
import okhttp3.RequestBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto-detects the audio source kind and returns a re-opener that yields
 * fresh request bodies per attempt. URL sources are handled separately —
 * use {@link #prepareUrl(String)} to get a sentinel URL holder.
 *
 * <p>Supports: {@code String} (URL or file path), {@link Path}, {@link File},
 * {@code byte[]}, {@link InputStream}.
 *
 * <p>The {@code String} branch tries URL first (must start with {@code
 * http://} / {@code https://}); otherwise treated as a file path and
 * verified to exist (better error message than a deferred FileNotFound on
 * upload).</p>
 */
public final class SourcePreparer {
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private SourcePreparer() {}

    /** Result of preparing a source. Exactly one of {@link #urlField()} or {@link #reopener()} is non-null. */
    public static final class Prepared {
        private final String urlField;
        private final SourceReopener reopener;

        private Prepared(String urlField, SourceReopener reopener) {
            this.urlField = urlField;
            this.reopener = reopener;
        }
        public String urlField() { return urlField; }
        public SourceReopener reopener() { return reopener; }
        public boolean isUrl() { return urlField != null; }
    }

    public static Prepared prepare(Object source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source is null; pass a URL string, a Path, a File, a byte[], or an InputStream");
        }
        if (source instanceof String) {
            String s = (String) source;
            if (looksLikeUrl(s)) {
                return new Prepared(s, null);
            }
            // Treat as file path; check existence to give a useful error
            // message rather than an HTTP error from an empty body.
            Path p = Path.of(s);
            if (!Files.exists(p)) {
                throw new IllegalArgumentException(
                    "\"" + s + "\" is not an HTTP URL (must start with http:// or https://) "
                    + "and is not an existing file path. Pass a URL, a Path, a File, an InputStream, or byte[].");
            }
            return new Prepared(null, () -> RequestBody.create(p.toFile(), OCTET));
        }
        if (source instanceof Path) {
            Path p = (Path) source;
            return new Prepared(null, () -> RequestBody.create(p.toFile(), OCTET));
        }
        if (source instanceof File) {
            File f = (File) source;
            return new Prepared(null, () -> RequestBody.create(f, OCTET));
        }
        if (source instanceof byte[]) {
            byte[] copy = ((byte[]) source).clone();
            return new Prepared(null, () -> RequestBody.create(copy, OCTET));
        }
        if (source instanceof InputStream) {
            // InputStreams aren't trivially re-readable — buffer once and reuse.
            byte[] buf = ((InputStream) source).readAllBytes();
            return new Prepared(null, () -> RequestBody.create(buf, OCTET));
        }
        throw new IllegalArgumentException("Unsupported source type: " + source.getClass().getName()
            + "; pass a URL string, a Path, a File, a byte[], or an InputStream.");
    }

    /** Convenience for explicitly URL-only callers — bypasses type detection. */
    public static Prepared prepareUrl(String url) {
        if (!looksLikeUrl(url)) {
            throw new IllegalArgumentException("not an http(s) URL: " + url);
        }
        return new Prepared(url, null);
    }

    static boolean looksLikeUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /** Helper for external callers needing the URL out of a Prepared. */
    public static String urlOf(SourceReopener reopener) {
        // Used by the type system; not currently invoked. Reserved for future expansion.
        return null;
    }
}
