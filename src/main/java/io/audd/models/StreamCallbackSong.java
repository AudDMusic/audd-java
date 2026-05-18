package io.audd.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One candidate song in a stream-callback recognition match. Almost every
 * match has exactly one song; multiple candidates only appear when the same
 * audio fingerprint resolves to several near-identical catalog records (and
 * those alternatives may have a different {@code artist} or {@code title} —
 * variant catalog releases, alternate catalog spellings, near-duplicates).
 */
public final class StreamCallbackSong extends ForwardCompatible {
    @JsonProperty("artist") public String artist;
    @JsonProperty("title") public String title;
    @JsonProperty("score") public Integer score;
    @JsonProperty("album") public String album;
    @JsonProperty("release_date") public String releaseDate;
    @JsonProperty("label") public String label;
    @JsonProperty("song_link") public String songLink;
    @JsonProperty("isrc") public String isrc;
    @JsonProperty("upc") public String upc;
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
    public String isrc() { return isrc; }
    public String upc() { return upc; }
    public AppleMusicMetadata appleMusic() { return appleMusic; }
    public SpotifyMetadata spotify() { return spotify; }
    public DeezerMetadata deezer() { return deezer; }
    public NapsterMetadata napster() { return napster; }
    public List<MusicBrainzEntry> musicbrainz() { return musicbrainz; }
}
