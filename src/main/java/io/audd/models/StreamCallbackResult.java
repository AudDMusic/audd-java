package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class StreamCallbackResult extends ForwardCompatible {
    @JsonProperty("radio_id") public Integer radioId;
    @JsonProperty("timestamp") public String timestamp;
    @JsonProperty("play_length") public Integer playLength;
    @JsonProperty("results") public List<StreamCallbackResultEntry> results;

    public Integer radioId() { return radioId; }
    public String timestamp() { return timestamp; }
    public Integer playLength() { return playLength; }
    public List<StreamCallbackResultEntry> results() { return results; }
}
