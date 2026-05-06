package io.audd.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A single match in an enterprise recognition response. */
public final class EnterpriseMatch extends ForwardCompatible {
    @JsonProperty("score") public Integer score;
    @JsonProperty("timecode") public String timecode;
    @JsonProperty("artist") public String artist;
    @JsonProperty("title") public String title;
    @JsonProperty("album") public String album;
    @JsonProperty("release_date") public String releaseDate;
    @JsonProperty("label") public String label;
    @JsonProperty("isrc") public String isrc;
    @JsonProperty("upc") public String upc;
    @JsonProperty("song_link") public String songLink;
    @JsonProperty("start_offset") public Integer startOffset;
    @JsonProperty("end_offset") public Integer endOffset;

    public Integer score() { return score; }
    public String timecode() { return timecode; }
    public String artist() { return artist; }
    public String title() { return title; }
    public String album() { return album; }
    public String releaseDate() { return releaseDate; }
    public String label() { return label; }
    public String isrc() { return isrc; }
    public String upc() { return upc; }
    public String songLink() { return songLink; }
    public Integer startOffset() { return startOffset; }
    public Integer endOffset() { return endOffset; }

    @JsonIgnore
    public String thumbnailUrl() {
        if (songLink == null || songLink.isEmpty()) return null;
        URI uri;
        try {
            uri = new URI(songLink);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"lis.tn".equals(uri.getHost())) return null;
        String sep = uri.getRawQuery() != null && !uri.getRawQuery().isEmpty() ? "&" : "?";
        return songLink + sep + "thumb";
    }

    /**
     * Redirect URL for a streaming provider — see
     * {@link RecognitionResult#streamingUrl(StreamingProvider)}.
     *
     * <p>Enterprise responses don't carry the per-provider metadata blocks, so
     * only the lis.tn redirect path applies. Returns {@code null} when
     * {@code songLink} is non-lis.tn (or null/empty). See design spec §4.3.
     */
    @JsonIgnore
    public String streamingUrl(StreamingProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        return StreamingUrls.lisTnRedirect(songLink, provider.slug());
    }

    /**
     * All providers with a lis.tn redirect URL available. Empty map for
     * non-lis.tn (or absent) song_link. See design spec §4.3.
     */
    @JsonIgnore
    public Map<StreamingProvider, String> streamingUrls() {
        Map<StreamingProvider, String> out = new LinkedHashMap<>();
        for (StreamingProvider p : StreamingProvider.values()) {
            String url = streamingUrl(p);
            if (url != null) out.put(p, url);
        }
        return out;
    }
}
