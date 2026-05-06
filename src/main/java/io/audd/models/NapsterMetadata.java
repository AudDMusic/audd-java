package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class NapsterMetadata extends ForwardCompatible {
    @JsonProperty("id") public String id;
    @JsonProperty("name") public String name;
    @JsonProperty("isrc") public String isrc;
    @JsonProperty("artistName") public String artistName;
    @JsonProperty("albumName") public String albumName;

    public String id() { return id; }
    public String name() { return name; }
    public String isrc() { return isrc; }
    public String artistName() { return artistName; }
    public String albumName() { return albumName; }
}
