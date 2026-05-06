package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class Stream extends ForwardCompatible {
    @JsonProperty("radio_id") public Integer radioId;
    @JsonProperty("url") public String url;
    @JsonProperty("stream_running") public Boolean streamRunning;
    @JsonProperty("longpoll_category") public String longpollCategory;

    public Integer radioId() { return radioId; }
    public String url() { return url; }
    public Boolean streamRunning() { return streamRunning; }
    public String longpollCategory() { return longpollCategory; }
}
