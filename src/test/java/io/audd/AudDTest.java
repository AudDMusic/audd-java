package io.audd;

import io.audd.errors.AudDAuthenticationError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDSerializationError;
import io.audd.errors.AudDServerError;
import io.audd.models.EnterpriseMatch;
import io.audd.models.RecognitionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudDTest {
    private MockWebServer server;
    private AudD audd;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audd = AudD.builder()
            .apiToken("test")
            .apiBase(server.url("/").toString().replaceAll("/$", ""))
            .enterpriseBase(server.url("/").toString().replaceAll("/$", ""))
            .maxRetries(1)
            .backoffFactorMs(1)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        audd.close();
        server.shutdown();
    }

    @Test
    void recognize_publicMatch_returnsTypedResult() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"success\",\"result\":{\"timecode\":\"00:56\",\"artist\":\"Tears For Fears\","
            + "\"title\":\"Everybody Wants To Rule The World\",\"song_link\":\"https://lis.tn/NbkVb\"}}"));

        RecognitionResult r = audd.recognize("https://example.com/audio.mp3");
        assertThat(r).isNotNull();
        assertThat(r.artist()).isEqualTo("Tears For Fears");
        assertThat(r.title()).isEqualTo("Everybody Wants To Rule The World");
        assertThat(r.thumbnailUrl()).isEqualTo("https://lis.tn/NbkVb?thumb");

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("api_token").contains("test");
        assertThat(body).contains("url").contains("https://example.com/audio.mp3");
    }

    @Test
    void recognize_noMatchReturnsNull() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));
        RecognitionResult r = audd.recognize("https://example.com/audio.mp3");
        assertThat(r).isNull();
    }

    @Test
    void recognize_900ErrorRaisesAuthenticationError() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"error\",\"error\":{\"error_code\":900,\"error_message\":\"bad token\"}}"));
        assertThatThrownBy(() -> audd.recognize("https://example.com/audio.mp3"))
            .isInstanceOf(AudDAuthenticationError.class);
    }

    @Test
    void recognize_appliesReturnAndMarketOptions() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));

        audd.recognize("https://example.com/audio.mp3",
            RecognizeOptions.builder().returnMetadata("apple_music", "spotify").market("gb").build());

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("apple_music,spotify");
        assertThat(body).contains("market").contains("gb");
    }

    @Test
    void recognize_502NonJsonRaisesServerErrorPreservingStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        assertThatThrownBy(() -> audd.recognize("https://example.com/audio.mp3"))
            .isInstanceOfSatisfying(AudDServerError.class, e -> {
                assertThat(e.httpStatus()).isEqualTo(502);
            });
    }

    @Test
    void recognize_2xxNonJsonRaisesSerializationError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>not json</html>"));
        assertThatThrownBy(() -> audd.recognize("https://example.com/audio.mp3"))
            .isInstanceOf(AudDSerializationError.class);
    }

    @Test
    void recognize_code51WithResult_emitsDeprecationAndReturnsResult() throws Exception {
        AtomicReference<String> deprecationMessage = new AtomicReference<>();
        AudD local = AudD.builder()
            .apiToken("test")
            .apiBase(server.url("/").toString().replaceAll("/$", ""))
            .maxRetries(1)
            .backoffFactorMs(1)
            .onDeprecation(deprecationMessage::set)
            .build();
        try {
            server.enqueue(new MockResponse().setBody(
                "{\"status\":\"error\",\"error\":{\"error_code\":51,\"error_message\":\"deprecated param\"},"
                + "\"result\":{\"timecode\":\"00:56\",\"artist\":\"X\",\"title\":\"Y\"}}"));
            RecognitionResult r = local.recognize("https://example.com/audio.mp3");
            assertThat(r).isNotNull();
            assertThat(r.artist()).isEqualTo("X");
            assertThat(deprecationMessage.get()).contains("deprecated param");
        } finally {
            local.close();
        }
    }

    @Test
    void recognize_code51WithoutResult_throwsInvalidRequest() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"error\",\"error\":{\"error_code\":51,\"error_message\":\"deprecated param\"}}"));
        assertThatThrownBy(() -> audd.recognize("https://example.com/audio.mp3"))
            .isInstanceOf(AudDInvalidRequestError.class);
    }

    @Test
    void recognizeEnterprise_flatList() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"success\",\"result\":[{\"songs\":[{\"score\":81,\"timecode\":\"00:57\","
            + "\"artist\":\"X\",\"title\":\"Y\",\"isrc\":\"GB\",\"upc\":\"00601\"}],\"offset\":\"00:00\"}]}"));

        List<EnterpriseMatch> matches = audd.recognizeEnterprise("https://example.com/audio.mp3",
            EnterpriseOptions.builder().limit(1).build());
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).artist()).isEqualTo("X");
        assertThat(matches.get(0).isrc()).isEqualTo("GB");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("limit").contains("1");
    }

    @Test
    void close_isIdempotent() {
        audd.close();
        audd.close(); // should not throw
    }

    @Test
    void hierarchicalSubclients_areLazilyCreated() {
        assertThat(audd.streams()).isNotNull();
        assertThat(audd.streams()).isSameAs(audd.streams());
        assertThat(audd.customCatalog()).isNotNull();
        assertThat(audd.advanced()).isNotNull();
    }
}
