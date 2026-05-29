package io.audd.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionResultTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void publicMatch_parsesAndExposesHelpers() throws Exception {
        String json = "{\"artist\":\"Tears For Fears\",\"title\":\"Everybody Wants To Rule The World\","
                + "\"album\":\"Songs From The Big Chair\",\"release_date\":\"2014-11-10\","
                + "\"label\":\"UMC\",\"timecode\":\"00:56\",\"song_link\":\"https://lis.tn/NbkVb\"}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.timecode()).isEqualTo("00:56");
        assertThat(r.artist()).isEqualTo("Tears For Fears");
        assertThat(r.title()).isEqualTo("Everybody Wants To Rule The World");
        assertThat(r.songLink()).isEqualTo("https://lis.tn/NbkVb");
        assertThat(r.isPublicMatch()).isTrue();
        assertThat(r.isCustomMatch()).isFalse();
        assertThat(r.thumbnailUrl()).isEqualTo("https://lis.tn/NbkVb?thumb");
    }

    @Test
    void customMatch_isCustomMatch_true() throws Exception {
        String json = "{\"timecode\":\"01:45\",\"audio_id\":146}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.isCustomMatch()).isTrue();
        assertThat(r.isPublicMatch()).isFalse();
        assertThat(r.audioId()).isEqualTo(146);
        assertThat(r.thumbnailUrl()).isNull();
    }

    @Test
    void thumbnailUrl_returnsNullForNonListnLink() throws Exception {
        String json = "{\"timecode\":\"00:00\",\"artist\":\"x\",\"title\":\"y\",\"song_link\":\"https://youtube.com/watch?v=1\"}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.thumbnailUrl()).isNull();
    }

    @Test
    void thumbnailUrl_appendsAmpersandWhenLinkAlreadyHasQuery() throws Exception {
        String json = "{\"timecode\":\"00:00\",\"artist\":\"x\",\"song_link\":\"https://lis.tn/abc?foo=1\"}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.thumbnailUrl()).isEqualTo("https://lis.tn/abc?foo=1&thumb");
    }

    @Test
    void unknownFields_landInExtras() throws Exception {
        String json = "{\"timecode\":\"00:00\",\"artist\":\"x\",\"newField\":\"value\",\"somenum\":42}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.extras()).containsKeys("newField", "somenum");
        assertThat(r.extras().get("newField")).isEqualTo("value");
        assertThat(r.extras().get("somenum")).isEqualTo(42);
    }

    @Test
    void rawResponse_canBeAttached() throws Exception {
        String json = "{\"timecode\":\"00:56\",\"artist\":\"a\"}";
        JsonNode tree = M.readTree(json);
        RecognitionResult r = M.treeToValue(tree, RecognitionResult.class);
        r.setRawResponse(tree);
        assertThat(r.rawResponse()).isNotNull();
        assertThat(r.rawResponse().get("timecode").asText()).isEqualTo("00:56");
    }

    @Test
    void appleMusicMetadata_parses() throws Exception {
        String json = "{\"timecode\":\"00:00\",\"apple_music\":{\"artistName\":\"X\",\"durationInMillis\":12345,\"genreNames\":[\"Pop\"]}}";
        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.appleMusic()).isNotNull();
        assertThat(r.appleMusic().artistName()).isEqualTo("X");
        assertThat(r.appleMusic().durationInMillis()).isEqualTo(12345);
        // genreNames is unknown to typed model — should land in extras.
        assertThat(r.appleMusic().extras()).containsKey("genreNames");
    }
}
