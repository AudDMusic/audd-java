package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** A single song match inside a stream callback result. */
public final class StreamCallbackResultEntry extends ForwardCompatible {
    @JsonProperty("artist") public String artist;
    @JsonProperty("title") public String title;
    @JsonProperty("score") public Integer score;
    @JsonProperty("album") public String album;
    @JsonProperty("release_date") public String releaseDate;
    @JsonProperty("label") public String label;
    @JsonProperty("song_link") public String songLink;
    @JsonProperty("apple_music") public AppleMusicMetadata appleMusic;
    @JsonProperty("spotify") public SpotifyMetadata spotify;
    @JsonProperty("deezer") public DeezerMetadata deezer;
    @JsonProperty("napster") public NapsterMetadata napster;
    @JsonProperty("musicbrainz") public List<MusicBrainzEntry> musicbrainz;

    public String artist() { return artist; }
    public String title() { return title; }
    public Integer score() { return score; }
    public String album() { return album; }
    public String releaseDate() { return releaseDate; }
    public String label() { return label; }
    public String songLink() { return songLink; }
    public AppleMusicMetadata appleMusic() { return appleMusic; }
    public SpotifyMetadata spotify() { return spotify; }
    public DeezerMetadata deezer() { return deezer; }
    public NapsterMetadata napster() { return napster; }
    public List<MusicBrainzEntry> musicbrainz() { return musicbrainz; }
}
