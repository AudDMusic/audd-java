package io.audd.streams;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameters for {@link Streams#add(AddStreamRequest)}.
 *
 * <p>{@code url} accepts direct stream URLs (DASH, Icecast, HLS, m3u/m3u8)
 * and shortcuts: {@code twitch:<channel>}, {@code youtube:<video_id>},
 * {@code youtube-ch:<channel_id>}.
 *
 * <p>{@code callbacks="before"} delivers callbacks at song start instead of
 * song end.
 *
 * <p>{@code extraParameters} are additional form fields the typed params don't
 * cover. Typed params ({@code url}, {@code radio_id}, {@code callbacks}) win on
 * collision.</p>
 *
 * <p>{@code radioId} is any integer you choose as your handle for the
 * stream; every constructor takes it as an {@code int} or a {@code long}.</p>
 */
public final class AddStreamRequest {
    private final String url;
    private final long radioId;
    private final String callbacks;
    private final Map<String, String> extraParameters;

    public AddStreamRequest(String url, int radioId) { this(url, (long) radioId, null, null); }

    public AddStreamRequest(String url, long radioId) { this(url, radioId, null, null); }

    public AddStreamRequest(String url, int radioId, String callbacks) {
        this(url, (long) radioId, callbacks, null);
    }

    public AddStreamRequest(String url, long radioId, String callbacks) {
        this(url, radioId, callbacks, null);
    }

    public AddStreamRequest(String url, int radioId, String callbacks, Map<String, String> extraParameters) {
        this(url, (long) radioId, callbacks, extraParameters);
    }

    public AddStreamRequest(String url, long radioId, String callbacks, Map<String, String> extraParameters) {
        this.url = url;
        this.radioId = radioId;
        this.callbacks = callbacks;
        this.extraParameters = extraParameters == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(extraParameters));
    }

    public String url() { return url; }

    /**
     * The radio_id as an {@code int}.
     *
     * @throws ArithmeticException if the request was built with a
     *     {@code long} radio_id outside the {@code int} range
     */
    public int radioId() { return Math.toIntExact(radioId); }

    /** The radio_id as sent to the server. */
    long radioIdValue() { return radioId; }

    public String callbacks() { return callbacks; }
    public Map<String, String> extraParameters() { return extraParameters; }
}
