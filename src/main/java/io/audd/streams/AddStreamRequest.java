package io.audd.streams;

/**
 * Parameters for {@link Streams#add(AddStreamRequest)}.
 *
 * <p>{@code url} accepts direct stream URLs (DASH, Icecast, HLS, m3u/m3u8)
 * and shortcuts: {@code twitch:<channel>}, {@code youtube:<video_id>},
 * {@code youtube-ch:<channel_id>}.
 *
 * <p>{@code callbacks="before"} delivers callbacks at song start instead of
 * song end.</p>
 */
public final class AddStreamRequest {
    private final String url;
    private final int radioId;
    private final String callbacks;

    public AddStreamRequest(String url, int radioId) { this(url, radioId, null); }

    public AddStreamRequest(String url, int radioId, String callbacks) {
        this.url = url;
        this.radioId = radioId;
        this.callbacks = callbacks;
    }

    public String url() { return url; }
    public int radioId() { return radioId; }
    public String callbacks() { return callbacks; }
}
