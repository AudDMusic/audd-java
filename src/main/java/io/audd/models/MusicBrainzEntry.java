package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class MusicBrainzEntry extends ForwardCompatible {
    @JsonProperty("id") public String id;
    /** Score may arrive as int or string from upstream. */
    @JsonProperty("score") public Object score;
    @JsonProperty("title") public String title;
    @JsonProperty("length") public Integer length;

    public String id() { return id; }
    public Object score() { return score; }
    public String title() { return title; }
    public Integer length() { return length; }
}
