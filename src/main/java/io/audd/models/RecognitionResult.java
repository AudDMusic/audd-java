package io.audd.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognition result from {@code /recognize}. Public-catalog and
 * custom-catalog matches share this single type — fields are nullable.
 *
 * <p>Use {@link #isCustomMatch()} / {@link #isPublicMatch()} to disambiguate.</p>
 */
public final class RecognitionResult extends ForwardCompatible {
    @JsonProperty("timecode") public String timecode;
    @JsonProperty("audio_id") public Integer audioId;
    @JsonProperty("artist") public String artist;
    @JsonProperty("title") public String title;
    @JsonProperty("album") public String album;
    @JsonProperty("release_date") public String releaseDate;
    @JsonProperty("label") public String label;
    @JsonProperty("song_link") public String songLink;
    @JsonProperty("isrc") public String isrc;
    @JsonProperty("upc") public String upc;
    @JsonProperty("apple_music") public AppleMusicMetadata appleMusic;
    @JsonProperty("spotify") public SpotifyMetadata spotify;
    @JsonProperty("deezer") public DeezerMetadata deezer;
    @JsonProperty("napster") public NapsterMetadata napster;
    @JsonProperty("musicbrainz") public List<MusicBrainzEntry> musicbrainz;

    public String timecode() { return timecode; }
    public Integer audioId() { return audioId; }
    public String artist() { return artist; }
    public String title() { return title; }
    public String album() { return album; }
    public String releaseDate() { return releaseDate; }
    public String label() { return label; }
    public String songLink() { return songLink; }
    public String isrc() { return isrc; }
    public String upc() { return upc; }
    public AppleMusicMetadata appleMusic() { return appleMusic; }
    public SpotifyMetadata spotify() { return spotify; }
    public DeezerMetadata deezer() { return deezer; }
    public NapsterMetadata napster() { return napster; }
    public List<MusicBrainzEntry> musicbrainz() { return musicbrainz; }

    @JsonIgnore
    public boolean isCustomMatch() { return audioId != null; }

    @JsonIgnore
    public boolean isPublicMatch() {
        return audioId == null && (artist != null || (title != null && !title.isEmpty()));
    }

    /**
     * Cover-art URL for {@code lis.tn}-hosted song_links, else {@code null}.
     * Appends {@code ?thumb} (or {@code &thumb} if the link already has a
     * query). Only the {@code lis.tn} host has the image endpoint.
     */
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
     * Direct or redirect URL for a streaming provider, with smart fallback.
     *
     * <p>Resolution order:
     * <ol>
     *   <li><strong>Direct URL from the metadata block</strong> when the user
     *       requested that provider via {@code return=} (e.g.
     *       {@code apple_music.url}, {@code spotify.external_urls.spotify},
     *       {@code deezer.link}, {@code napster.href}). Direct = no redirect,
     *       faster for clients.</li>
     *   <li><strong>lis.tn redirect</strong> {@code "<songLink>?<provider>"}
     *       when {@code songLink} is a lis.tn URL. Works regardless of
     *       whether {@code return=} was set.</li>
     *   <li>{@code null} when neither path resolves (e.g., a YouTube
     *       {@code songLink} and the user didn't request the provider's
     *       metadata).</li>
     * </ol>
     *
     * <p>{@link StreamingProvider#YOUTUBE} has no metadata-block fallback —
     * only the lis.tn redirect path applies. See design spec §4.3.
     */
    @JsonIgnore
    public String streamingUrl(StreamingProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        String direct = directStreamingUrl(provider);
        if (direct != null) return direct;
        return StreamingUrls.lisTnRedirect(songLink, provider.slug());
    }

    /** Pull a direct URL out of the corresponding metadata block, if present. */
    private String directStreamingUrl(StreamingProvider provider) {
        switch (provider) {
            case APPLE_MUSIC:
                if (appleMusic != null && appleMusic.url != null && !appleMusic.url.isEmpty()) {
                    return appleMusic.url;
                }
                return null;
            case SPOTIFY:
                if (spotify != null) {
                    Object ext = spotify.extras().get("external_urls");
                    if (ext instanceof Map<?, ?>) {
                        Object url = ((Map<?, ?>) ext).get("spotify");
                        if (url instanceof String && !((String) url).isEmpty()) {
                            return (String) url;
                        }
                    }
                    if (spotify.uri != null && !spotify.uri.isEmpty()) {
                        return spotify.uri;
                    }
                }
                return null;
            case DEEZER:
                if (deezer != null && deezer.link != null && !deezer.link.isEmpty()) {
                    return deezer.link;
                }
                return null;
            case NAPSTER:
                if (napster != null) {
                    Object href = napster.extras().get("href");
                    if (href instanceof String && !((String) href).isEmpty()) {
                        return (String) href;
                    }
                }
                return null;
            case YOUTUBE:
            default:
                return null;
        }
    }

    /**
     * All providers with a resolvable URL — direct or via lis.tn redirect.
     *
     * <p>Returns a map {@code provider -> url} for every provider where either
     * the metadata block carries a direct URL OR the {@code songLink} is a
     * lis.tn URL. Empty map if neither path resolves for any provider.
     * Iteration order is the {@link StreamingProvider} declaration order.
     * See design spec §4.3.
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

    /**
     * First available 30-second audio preview URL, in priority order.
     *
     * <p>Picks the first non-empty URL from {@code apple_music.previews[0].url}
     * → {@code spotify.preview_url} → {@code deezer.preview}. Returns
     * {@code null} if no metadata block carries a preview.
     *
     * <p><strong>Note:</strong> previews are governed by their respective
     * providers' terms of use (Apple Music, Spotify, Deezer). The SDK consumer
     * is responsible for honoring those terms — including caching restrictions,
     * attribution requirements, and any redistribution constraints.
     */
    @JsonIgnore
    public String previewUrl() {
        if (appleMusic != null) {
            Object previews = appleMusic.extras().get("previews");
            if (previews instanceof List<?>) {
                List<?> list = (List<?>) previews;
                if (!list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
                    Object url = ((Map<?, ?>) list.get(0)).get("url");
                    if (url instanceof String && !((String) url).isEmpty()) {
                        return (String) url;
                    }
                }
            }
        }
        if (spotify != null) {
            Object previewUrl = spotify.extras().get("preview_url");
            if (previewUrl instanceof String && !((String) previewUrl).isEmpty()) {
                return (String) previewUrl;
            }
        }
        if (deezer != null) {
            Object preview = deezer.extras().get("preview");
            if (preview instanceof String && !((String) preview).isEmpty()) {
                return (String) preview;
            }
        }
        return null;
    }
}
