package io.audd.models;

/**
 * Streaming services reachable via the lis.tn {@code ?<provider>} redirect
 * helper and (where applicable) via direct URLs in the matching metadata
 * block. See design spec §4.3.
 *
 * <p>Use with {@link RecognitionResult#streamingUrl(StreamingProvider)} and
 * {@link EnterpriseMatch#streamingUrl(StreamingProvider)}.
 */
public enum StreamingProvider {
    SPOTIFY("spotify"),
    APPLE_MUSIC("apple_music"),
    DEEZER("deezer"),
    NAPSTER("napster"),
    YOUTUBE("youtube");

    private final String slug;

    StreamingProvider(String slug) {
        this.slug = slug;
    }

    /**
     * Wire slug used in lis.tn redirect URLs (e.g., {@code "spotify"} appended
     * as {@code ?spotify}). Stable identifier for forward compatibility.
     */
    public String slug() {
        return slug;
    }
}
