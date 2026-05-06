package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** One chunk of an enterprise recognition response — a list of songs + offset. */
public final class EnterpriseChunkResult extends ForwardCompatible {
    @JsonProperty("songs") public List<EnterpriseMatch> songs;
    @JsonProperty("offset") public String offset;

    public List<EnterpriseMatch> songs() { return songs; }
    public String offset() { return offset; }
}
