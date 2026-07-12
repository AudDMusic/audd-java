package io.audd.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.internal.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The enterprise endpoint legitimately returns matches with no {@code score}
 * (and no {@code isrc}/{@code upc}/{@code label}). A successful response must
 * never fail to parse because such a field is absent — or because the body
 * carries keys the SDK does not model yet. These tests pin that contract
 * against the same lenient mapper the client uses.
 */
class EnterpriseLenientParseTest {
    private static final ObjectMapper M = Json.mapper();

    @Test
    void enterpriseSong_withoutScore_parsesAndScoreIsNull() throws Exception {
        // An enterprise chunk whose single song omits score/isrc/upc/label.
        String json = "{\"songs\":[{"
            + "\"artist\":\"Nirvana\",\"title\":\"Smells Like Teen Spirit\","
            + "\"album\":\"Nevermind\",\"timecode\":\"00:42\","
            + "\"song_link\":\"https://lis.tn/AbCdE\"}],"
            + "\"offset\":\"0\"}";

        EnterpriseChunkResult chunk = M.readValue(json, EnterpriseChunkResult.class);

        assertThat(chunk.offset()).isEqualTo("0");
        List<EnterpriseMatch> songs = chunk.songs();
        assertThat(songs).hasSize(1);

        EnterpriseMatch m = songs.get(0);
        assertThat(m.artist()).isEqualTo("Nirvana");
        assertThat(m.title()).isEqualTo("Smells Like Teen Spirit");
        // The fields the enterprise endpoint may omit are simply null — no throw.
        assertThat(m.score()).isNull();
        assertThat(m.isrc()).isNull();
        assertThat(m.upc()).isNull();
        assertThat(m.label()).isNull();
        // Helpers still work off the present song_link.
        assertThat(m.thumbnailUrl()).isEqualTo("https://lis.tn/AbCdE?thumb");
    }

    @Test
    void enterpriseSong_withUnknownFields_doesNotThrow() {
        // Server adds keys the SDK doesn't model yet — must be absorbed, not fatal.
        String json = "{\"songs\":[{"
            + "\"artist\":\"x\",\"title\":\"y\",\"score\":98,"
            + "\"some_future_field\":{\"nested\":true},\"another\":[1,2,3]}]}";

        assertThatCode(() -> {
            EnterpriseChunkResult chunk = M.readValue(json, EnterpriseChunkResult.class);
            EnterpriseMatch m = chunk.songs().get(0);
            assertThat(m.score()).isEqualTo(98);
            // Unknown keys are retained on the forward-compatible extras map.
            assertThat(m.extras()).containsKey("some_future_field");
            assertThat(m.extras()).containsKey("another");
        }).doesNotThrowAnyException();
    }

    @Test
    void enterpriseResponse_withEmptyAndAbsentArrays_doesNotThrow() {
        assertThatCode(() -> {
            // Empty songs array.
            M.readValue("{\"songs\":[],\"offset\":\"10\"}", EnterpriseChunkResult.class);
            // songs absent entirely.
            EnterpriseChunkResult c = M.readValue("{\"offset\":\"10\"}", EnterpriseChunkResult.class);
            assertThat(c.songs()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void enterpriseSong_withScoreAsNonNumericString_degradesToNull() throws Exception {
        // score wired as a non-numeric string must degrade to null, not throw.
        String json = "{\"songs\":[{\"artist\":\"x\",\"title\":\"y\",\"score\":\"n/a\"}]}";

        EnterpriseChunkResult chunk = M.readValue(json, EnterpriseChunkResult.class);
        EnterpriseMatch m = chunk.songs().get(0);
        assertThat(m.artist()).isEqualTo("x");
        assertThat(m.score()).isNull();
    }

    @Test
    void enterpriseSong_withProviderBlockAsString_degradesAndKeepsSiblings() throws Exception {
        // A nested provider-shaped field arriving as a bare string is skipped;
        // the rest of the song still decodes.
        String json = "{\"songs\":[{\"artist\":\"x\",\"title\":\"y\","
            + "\"start_offset\":1500,\"apple_music\":\"unavailable\"}]}";

        EnterpriseChunkResult chunk = M.readValue(json, EnterpriseChunkResult.class);
        EnterpriseMatch m = chunk.songs().get(0);
        assertThat(m.title()).isEqualTo("y");
        assertThat(m.startOffset()).isEqualTo(1500);
    }

    @Test
    void recognitionResult_withAudioIdAsNonNumericString_degradesToNull() throws Exception {
        String json = "{\"artist\":\"a\",\"title\":\"t\",\"audio_id\":\"not-a-number\"}";

        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.artist()).isEqualTo("a");
        assertThat(r.audioId()).isNull();
    }

    @Test
    void recognitionResult_withSpotifyNameAsObject_degradesFieldNotWholeBlock() throws Exception {
        // spotify.name (a String field) arrives as an object: skip the offending
        // value, keep the rest of the spotify block and the outer result.
        String json = "{\"artist\":\"a\",\"title\":\"t\",\"spotify\":{"
            + "\"id\":\"abc123\",\"name\":{\"unexpected\":\"object\"},\"popularity\":77}}";

        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.title()).isEqualTo("t");
        assertThat(r.spotify()).isNotNull();
        assertThat(r.spotify().name()).isNull();
        assertThat(r.spotify().id).isEqualTo("abc123");
        assertThat(r.spotify().popularity).isEqualTo(77);
    }

    @Test
    void recognitionResult_withSpotifyBlockAsString_degradesToNull() throws Exception {
        // Whole spotify object typed as a scalar arriving as a string.
        String json = "{\"artist\":\"a\",\"title\":\"t\",\"spotify\":\"n/a\"}";

        RecognitionResult r = M.readValue(json, RecognitionResult.class);
        assertThat(r.artist()).isEqualTo("a");
        assertThat(r.spotify()).isNull();
    }
}
