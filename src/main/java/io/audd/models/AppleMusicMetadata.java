package io.audd.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class AppleMusicMetadata extends ForwardCompatible {
    @JsonProperty("artistName") public String artistName;
    @JsonProperty("url") public String url;
    @JsonProperty("durationInMillis") public Integer durationInMillis;
    @JsonProperty("name") public String name;
    @JsonProperty("isrc") public String isrc;
    @JsonProperty("albumName") public String albumName;
    @JsonProperty("trackNumber") public Integer trackNumber;
    @JsonProperty("composerName") public String composerName;
    @JsonProperty("discNumber") public Integer discNumber;
    @JsonProperty("releaseDate") public String releaseDate;

    public String artistName() { return artistName; }
    public String url() { return url; }
    public Integer durationInMillis() { return durationInMillis; }
    public String name() { return name; }
    public String isrc() { return isrc; }
    public String albumName() { return albumName; }
    public Integer trackNumber() { return trackNumber; }
    public String composerName() { return composerName; }
    public Integer discNumber() { return discNumber; }
    public String releaseDate() { return releaseDate; }
}
