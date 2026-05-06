package io.audd.streams;

import io.audd.AudD;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.models.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamsTest {
    private MockWebServer server;
    private AudD audd;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        audd = AudD.builder()
            .apiToken("test")
            .apiBase(server.url("/").toString().replaceAll("/$", ""))
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
    void list_parsesStreamsArray() throws Exception {
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"success\",\"result\":[{\"radio_id\":1,\"url\":\"https://x\","
            + "\"stream_running\":true,\"longpoll_category\":\"abc123def\"}]}"));
        List<Stream> streams = audd.streams().list();
        assertThat(streams).hasSize(1);
        assertThat(streams.get(0).radioId()).isEqualTo(1);
        assertThat(streams.get(0).streamRunning()).isTrue();
    }

    @Test
    void list_emptyArrayReturnsEmptyList() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":[]}"));
        List<Stream> streams = audd.streams().list();
        assertThat(streams).isEmpty();
    }

    @Test
    void getCallbackUrl_returnsString() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"https://example.com/cb\"}"));
        String url = audd.streams().getCallbackUrl();
        assertThat(url).isEqualTo("https://example.com/cb");
    }

    @Test
    void setCallbackUrl_simple() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().setCallbackUrl("https://example.com/cb");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("setCallbackUrl");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("https://example.com/cb");
    }

    @Test
    void setCallbackUrl_withReturnMetadataAppendsParam() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().setCallbackUrl("https://example.com/cb", List.of("apple_music", "spotify"));
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("return=apple_music").contains("spotify");
    }

    @Test
    void add_passesUrlAndRadioId() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().add(new AddStreamRequest("https://npr-ice.streamguys1.com/live.mp3", 999001));
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("addStream");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("999001");
        assertThat(body).contains("https://npr-ice.streamguys1.com/live.mp3");
    }

    @Test
    void add_withCallbacksBefore() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().add(new AddStreamRequest("twitch:rocketbeanstv", 100, "before"));
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("callbacks").contains("before");
    }

    @Test
    void delete_passesRadioId() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().delete(42);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("deleteStream");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("radio_id").contains("42");
    }

    @Test
    void setUrl_passesBoth() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));
        audd.streams().setUrl(7, "https://new-url.example.com");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("setStreamUrl");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("https://new-url.example.com");
    }

    @Test
    void longpoll_preflightFiresGetCallbackUrlByDefault() throws Exception {
        // 1) preflight: getCallbackUrl returns success
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"https://cb\"}"));
        // 2) first longpoll response
        server.enqueue(new MockResponse().setBody("{\"timeout\":\"no events before timeout\",\"timestamp\":1}"));

        Iterator<com.fasterxml.jackson.databind.JsonNode> it = audd.streams().longpoll("abc");
        var first = it.next();
        assertThat(first.path("timeout").asText()).isEqualTo("no events before timeout");

        RecordedRequest preflight = server.takeRequest();
        assertThat(preflight.getPath()).contains("getCallbackUrl");
        RecordedRequest poll = server.takeRequest();
        assertThat(poll.getPath()).contains("/longpoll/").contains("category=abc");
    }

    @Test
    void longpoll_preflightCode19RaisesHelpfulError() throws Exception {
        // server replies with code-19 to signal "no callback URL configured"
        server.enqueue(new MockResponse().setBody(
            "{\"status\":\"error\",\"error\":{\"error_code\":19,\"error_message\":\"Internal error.\"}}"));
        assertThatThrownBy(() -> audd.streams().longpoll("abc"))
            .isInstanceOfSatisfying(AudDInvalidRequestError.class, e -> {
                assertThat(e.serverMessage()).contains("no callback URL is configured");
                assertThat(e.serverMessage()).contains("skipCallbackCheck");
            });
    }

    @Test
    void longpoll_skipCallbackCheckBypassesPreflight() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"timeout\":\"no events before timeout\",\"timestamp\":1}"));
        Iterator<com.fasterxml.jackson.databind.JsonNode> it = audd.streams().longpoll("abc",
            LongpollOptions.builder().skipCallbackCheck(true).build());
        var first = it.next();
        assertThat(first.path("timeout").asText()).isEqualTo("no events before timeout");
        // there should have been only one request — the longpoll itself
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void parseCallback_isAvailableStatically() throws Exception {
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"status\":\"-\",\"notification\":{\"radio_id\":1,\"notification_code\":650,\"notification_message\":\"x\"},\"time\":1}");
        var p = Streams.parseCallback(body);
        assertThat(p.isNotification()).isTrue();
    }

    @Test
    void deriveLongpollCategory_works() {
        String c = audd.streams().deriveLongpollCategory(42);
        assertThat(c).hasSize(9);
    }
}
