package io.audd.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecognitionResult#streamingUrl(StreamingProvider)},
 * {@link RecognitionResult#streamingUrls()}, and
 * {@link RecognitionResult#previewUrl()} resolution rules. See design spec
 * §4.3.
 */
class StreamingUrlsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RecognitionResult parseResult(String json) throws Exception {
        return MAPPER.readValue(json, RecognitionResult.class);
    }

    // ---- streamingUrl: lis.tn redirect path ---------------------------------

    @Test
    void streamingUrl_lisTnRedirect() throws Exception {
        RecognitionResult r = parseResult("{\"timecode\":\"00:01\",\"song_link\":\"https://lis.tn/abc\"}");
        assertThat(r.streamingUrl(StreamingProvider.SPOTIFY)).isEqualTo("https://lis.tn/abc?spotify");
        assertThat(r.streamingUrl(StreamingProvider.APPLE_MUSIC)).isEqualTo("https://lis.tn/abc?apple_music");
        assertThat(r.streamingUrl(StreamingProvider.DEEZER)).isEqualTo("https://lis.tn/abc?deezer");
        assertThat(r.streamingUrl(StreamingProvider.NAPSTER)).isEqualTo("https://lis.tn/abc?napster");
        assertThat(r.streamingUrl(StreamingProvider.YOUTUBE)).isEqualTo("https://lis.tn/abc?youtube");
    }

    @Test
    void streamingUrl_returnsNullForYouTubeSongLinkWithNoMetadata() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\"}");
        assertThat(r.streamingUrl(StreamingProvider.SPOTIFY)).isNull();
        assertThat(r.streamingUrl(StreamingProvider.YOUTUBE)).isNull();
    }

    @Test
    void streamingUrl_returnsNullWhenSongLinkAbsent() throws Exception {
        RecognitionResult r = parseResult("{\"timecode\":\"00:01\"}");
        assertThat(r.streamingUrl(StreamingProvider.SPOTIFY)).isNull();
    }

    // ---- streamingUrl: direct (return=) wins over redirect ------------------

    @Test
    void streamingUrl_directAppleMusicUrlWinsOverLisTnWhenBothAvailable() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://lis.tn/abc\","
                        + "\"apple_music\":{\"url\":\"https://music.apple.com/us/album/x/123?i=456\"}}");
        // Direct beats redirect (no extra hop).
        assertThat(r.streamingUrl(StreamingProvider.APPLE_MUSIC))
                .isEqualTo("https://music.apple.com/us/album/x/123?i=456");
    }

    @Test
    void streamingUrl_directDeezerLinkForNonLisTnSongLink() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://www.youtube.com/watch?v=x\","
                        + "\"deezer\":{\"link\":\"https://www.deezer.com/track/12345\"}}");
        assertThat(r.streamingUrl(StreamingProvider.DEEZER)).isEqualTo("https://www.deezer.com/track/12345");
    }

    @Test
    void streamingUrl_spotifyExternalUrlsFallback() throws Exception {
        // external_urls.spotify is read out of the JsonAnyGetter extras.
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://www.youtube.com/watch?v=x\","
                        + "\"spotify\":{\"id\":\"abc\",\"external_urls\":{\"spotify\":\"https://open.spotify.com/track/abc\"}}}");
        assertThat(r.streamingUrl(StreamingProvider.SPOTIFY))
                .isEqualTo("https://open.spotify.com/track/abc");
    }

    @Test
    void streamingUrl_napsterHrefFromExtras() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://www.youtube.com/watch?v=x\","
                        + "\"napster\":{\"id\":\"x\",\"href\":\"https://api.napster.com/v2.2/tracks/abc\"}}");
        assertThat(r.streamingUrl(StreamingProvider.NAPSTER))
                .isEqualTo("https://api.napster.com/v2.2/tracks/abc");
    }

    // ---- streamingUrls: bulk resolution -------------------------------------

    @Test
    void streamingUrls_listsEveryResolvableProviderForLisTn() throws Exception {
        RecognitionResult r = parseResult("{\"timecode\":\"00:01\",\"song_link\":\"https://lis.tn/abc\"}");
        Map<StreamingProvider, String> urls = r.streamingUrls();
        assertThat(urls).containsOnlyKeys(StreamingProvider.values());
        assertThat(urls.get(StreamingProvider.YOUTUBE)).endsWith("?youtube");
    }

    // ---- previewUrl: priority chain -----------------------------------------

    @Test
    void previewUrl_appleMusicWinsOverSpotifyAndDeezer() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\","
                        + "\"apple_music\":{\"previews\":[{\"url\":\"https://am.example/p.m4a\"}]},"
                        + "\"spotify\":{\"id\":\"x\",\"preview_url\":\"https://sp.example/p.mp3\"},"
                        + "\"deezer\":{\"id\":1,\"preview\":\"https://dz.example/p.mp3\"}}");
        assertThat(r.previewUrl()).isEqualTo("https://am.example/p.m4a");
    }

    @Test
    void previewUrl_fallsThroughToSpotifyWhenAppleMusicAbsent() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\","
                        + "\"spotify\":{\"id\":\"x\",\"preview_url\":\"https://sp.example/p.mp3\"}}");
        assertThat(r.previewUrl()).isEqualTo("https://sp.example/p.mp3");
    }

    @Test
    void previewUrl_fallsThroughToDeezerWhenAppleAndSpotifyAbsent() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"deezer\":{\"id\":1,\"preview\":\"https://dz.example/p.mp3\"}}");
        assertThat(r.previewUrl()).isEqualTo("https://dz.example/p.mp3");
    }

    @Test
    void previewUrl_returnsNullWhenNoMetadataBlocksHavePreview() throws Exception {
        RecognitionResult r = parseResult(
                "{\"timecode\":\"00:01\",\"song_link\":\"https://lis.tn/abc\"}");
        assertThat(r.previewUrl()).isNull();
    }

    // ---- EnterpriseMatch: lis.tn-only path ----------------------------------

    @Test
    void enterpriseMatch_streamingUrlIsLisTnOnly() throws Exception {
        EnterpriseMatch m = MAPPER.readValue(
                "{\"score\":80,\"timecode\":\"00:01\",\"song_link\":\"https://lis.tn/xyz\"}",
                EnterpriseMatch.class);
        assertThat(m.streamingUrl(StreamingProvider.SPOTIFY)).isEqualTo("https://lis.tn/xyz?spotify");
        assertThat(m.streamingUrls()).hasSize(StreamingProvider.values().length);
    }

    @Test
    void enterpriseMatch_streamingUrlsEmptyForNonLisTn() throws Exception {
        EnterpriseMatch m = MAPPER.readValue(
                "{\"score\":80,\"timecode\":\"00:01\",\"song_link\":\"https://www.youtube.com/watch?v=x\"}",
                EnterpriseMatch.class);
        assertThat(m.streamingUrl(StreamingProvider.SPOTIFY)).isNull();
        assertThat(m.streamingUrls()).isEmpty();
    }
}
