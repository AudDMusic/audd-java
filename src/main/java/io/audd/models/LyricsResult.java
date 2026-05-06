package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class LyricsResult extends ForwardCompatible {
    @JsonProperty("artist") public String artist;
    @JsonProperty("title") public String title;
    @JsonProperty("lyrics") public String lyrics;
    @JsonProperty("song_id") public Long songId;
    @JsonProperty("media") public String media;
    @JsonProperty("full_title") public String fullTitle;
    @JsonProperty("artist_id") public Long artistId;
    @JsonProperty("song_link") public String songLink;

    public String artist() { return artist; }
    public String title() { return title; }
    public String lyrics() { return lyrics; }
    public Long songId() { return songId; }
    public String media() { return media; }
    public String fullTitle() { return fullTitle; }
    public Long artistId() { return artistId; }
    public String songLink() { return songLink; }
}
