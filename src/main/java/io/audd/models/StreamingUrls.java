package io.audd.models;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Internal helper: resolve {@code lis.tn?<provider>} redirect URLs from a
 * {@code song_link}. Package-private; consumers should use
 * {@link RecognitionResult#streamingUrl(StreamingProvider)} or
 * {@link EnterpriseMatch#streamingUrl(StreamingProvider)} instead.
 *
 * <p>See design spec §4.3.
 */
final class StreamingUrls {
    private StreamingUrls() {}

    /**
     * Return {@code "<songLink>?<provider>"} only when {@code songLink} is on
     * {@code lis.tn}. Returns {@code null} for non-lis.tn (e.g., YouTube
     * song_links) and when {@code songLink} is null/empty.
     */
    static String lisTnRedirect(String songLink, String providerSlug) {
        if (songLink == null || songLink.isEmpty()) return null;
        URI uri;
        try {
            uri = new URI(songLink);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"lis.tn".equals(uri.getHost())) return null;
        String sep = uri.getRawQuery() != null && !uri.getRawQuery().isEmpty() ? "&" : "?";
        return songLink + sep + providerSlug;
    }
}
