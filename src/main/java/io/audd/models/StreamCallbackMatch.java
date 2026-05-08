package io.audd.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * One recognition event from a stream callback or longpoll. Carries the top
 * match in {@link #song()}; rare extra candidates live in
 * {@link #alternatives()} (which may have a different artist or title from
 * the top song — variant catalog releases, alternate catalog spellings,
 * near-duplicates of the same fingerprint).
 *
 * <p>This is a flat type — {@code radio_id}, {@code timestamp},
 * {@code play_length} sit alongside the matched song(s). The original
 * server payload's {@code result.results[]} array becomes
 * {@code [song] + alternatives}.</p>
 */
public final class StreamCallbackMatch extends ForwardCompatible {
    @JsonProperty("radio_id") public Long radioId;
    @JsonProperty("timestamp") public String timestamp;
    @JsonProperty("play_length") public Integer playLength;

    @JsonIgnore private StreamCallbackSong song;
    @JsonIgnore private List<StreamCallbackSong> alternatives = Collections.emptyList();

    public Long radioId() { return radioId; }
    public String timestamp() { return timestamp; }
    public Integer playLength() { return playLength; }

    /** The top match. Always non-null on a successful parse. */
    public StreamCallbackSong song() { return song; }

    /** Alternative candidates. Possibly empty; never null. */
    public List<StreamCallbackSong> alternatives() { return alternatives; }

    /** Internal: invoked by the parser. */
    public void setSong(StreamCallbackSong song) { this.song = song; }
    /** Internal: invoked by the parser. */
    public void setAlternatives(List<StreamCallbackSong> alternatives) {
        this.alternatives = alternatives == null ? Collections.emptyList() : alternatives;
    }
}
