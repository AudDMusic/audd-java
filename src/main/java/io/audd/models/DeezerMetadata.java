package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DeezerMetadata extends ForwardCompatible {
    @JsonProperty("id") public Long id;
    @JsonProperty("title") public String title;
    @JsonProperty("duration") public Integer duration;
    @JsonProperty("link") public String link;

    public Long id() { return id; }
    public String title() { return title; }
    public Integer duration() { return duration; }
    public String link() { return link; }
}
