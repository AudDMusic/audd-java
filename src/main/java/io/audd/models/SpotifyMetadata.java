package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class SpotifyMetadata extends ForwardCompatible {
    @JsonProperty("id") public String id;
    @JsonProperty("name") public String name;
    @JsonProperty("duration_ms") public Integer durationMs;
    @JsonProperty("explicit") public Boolean explicit;
    @JsonProperty("popularity") public Integer popularity;
    @JsonProperty("track_number") public Integer trackNumber;
    @JsonProperty("type") public String type;
    @JsonProperty("uri") public String uri;

    public String id() { return id; }
    public String name() { return name; }
    public Integer durationMs() { return durationMs; }
    public Boolean explicit() { return explicit; }
    public Integer popularity() { return popularity; }
    public Integer trackNumber() { return trackNumber; }
    public String type() { return type; }
    public String uri() { return uri; }
}
