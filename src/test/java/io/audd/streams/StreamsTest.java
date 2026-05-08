package io.audd.streams;

import io.audd.AsyncAudD;
import io.audd.AudD;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.models.Stream;
import io.audd.models.StreamCallbackMatch;
import io.audd.models.StreamCallbackNotification;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        // 2) first longpoll response — empty-window envelope
        server.enqueue(new MockResponse().setBody("{\"timeout\":\"no events before timeout\",\"timestamp\":1}"));
        // 3) second poll: a real match — used to drive the loop forward and
        //    then close it from inside the callback.
        server.enqueue(new MockResponse().setBody(
            "{\"result\":{\"radio_id\":7,\"timestamp\":\"2020-04-13 10:31:43\","
            + "\"results\":[{\"artist\":\"a\",\"title\":\"t\"}]},\"timestamp\":2}"));

        try (LongpollPoll poll = audd.streams().longpoll("abc")) {
            AtomicReference<StreamCallbackMatch> got = new AtomicReference<>();
            poll.onMatch(m -> { got.set(m); poll.close(); });
            poll.run();
            assertThat(got.get()).isNotNull();
            assertThat(got.get().song().artist()).isEqualTo("a");
        }

        RecordedRequest preflight = server.takeRequest();
        assertThat(preflight.getPath()).contains("getCallbackUrl");
        RecordedRequest firstPoll = server.takeRequest();
        assertThat(firstPoll.getPath()).contains("/longpoll/").contains("category=abc");
        RecordedRequest secondPoll = server.takeRequest();
        // since_time should be passed on the second poll
        assertThat(secondPoll.getPath()).contains("since_time=1");
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
        // Notification — drives the loop and gives us something to assert on.
        server.enqueue(new MockResponse().setBody(
            "{\"notification\":{\"radio_id\":3,\"notification_code\":650,\"notification_message\":\"oops\"},"
            + "\"time\":1587939136,\"timestamp\":1}"));
        try (LongpollPoll poll = audd.streams().longpoll("abc",
            LongpollOptions.builder().skipCallbackCheck(true).build())) {
            AtomicReference<StreamCallbackNotification> got = new AtomicReference<>();
            poll.onNotification(n -> { got.set(n); poll.close(); });
            poll.run();
            assertThat(got.get()).isNotNull();
            assertThat(got.get().notificationCode()).isEqualTo(650);
            assertThat(got.get().time()).isEqualTo(1587939136L);
        }
        // there should have been only one request — the longpoll itself
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void longpoll_runAsyncCompletesOnClose() throws Exception {
        // Empty-window response repeating until close.
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setBody(
                "{\"timeout\":\"no events before timeout\",\"timestamp\":" + (i + 1) + "}"));
        }
        LongpollPoll poll = audd.streams().longpoll("abc",
            LongpollOptions.builder().skipCallbackCheck(true).build());
        CompletableFuture<Void> done = poll.runAsync();
        // Close after a brief moment — verifies idempotent close + clean exit.
        Thread.sleep(50);
        poll.close();
        done.get(5, TimeUnit.SECONDS);
        assertThat(poll.isClosed()).isTrue();
    }

    @Test
    void parseCallback_isAvailableStatically() throws Exception {
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            "{\"status\":\"-\",\"notification\":{\"radio_id\":1,\"notification_code\":650,\"notification_message\":\"x\"},\"time\":1}");
        var event = Streams.parseCallback(body);
        assertThat(event.isNotification()).isTrue();
    }

    @Test
    void deriveLongpollCategory_works() {
        String c = audd.streams().deriveLongpollCategory(42);
        assertThat(c).hasSize(9);
    }

    @Test
    void longpoll_byRadioId_derivesCategoryAndDelegates() throws Exception {
        // 1) preflight: getCallbackUrl returns success
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"https://cb\"}"));
        // 2) longpoll first window — empty
        server.enqueue(new MockResponse().setBody("{\"timeout\":\"no events before timeout\",\"timestamp\":1}"));
        // 3) second window — drives a match so we can close from inside the callback
        server.enqueue(new MockResponse().setBody(
            "{\"result\":{\"radio_id\":42,\"timestamp\":\"2020-04-13 10:31:43\","
            + "\"results\":[{\"artist\":\"a\",\"title\":\"t\"}]},\"timestamp\":2}"));

        String expectedCategory = audd.streams().deriveLongpollCategory(42);
        try (LongpollPoll poll = audd.streams().longpoll(42)) {
            AtomicReference<StreamCallbackMatch> got = new AtomicReference<>();
            poll.onMatch(m -> { got.set(m); poll.close(); });
            poll.run();
            assertThat(got.get()).isNotNull();
        }

        RecordedRequest preflight = server.takeRequest();
        assertThat(preflight.getPath()).contains("getCallbackUrl");
        RecordedRequest firstPoll = server.takeRequest();
        assertThat(firstPoll.getPath()).contains("/longpoll/").contains("category=" + expectedCategory);
    }

    @Test
    void longpoll_byRadioId_withOptionsSkipsPreflight() throws Exception {
        // Single longpoll response — notification drives the loop and lets us close.
        server.enqueue(new MockResponse().setBody(
            "{\"notification\":{\"radio_id\":42,\"notification_code\":650,\"notification_message\":\"oops\"},"
            + "\"time\":1587939136,\"timestamp\":1}"));

        String expectedCategory = audd.streams().deriveLongpollCategory(42);
        try (LongpollPoll poll = audd.streams().longpoll(42,
            LongpollOptions.builder().skipCallbackCheck(true).build())) {
            AtomicReference<StreamCallbackNotification> got = new AtomicReference<>();
            poll.onNotification(n -> { got.set(n); poll.close(); });
            poll.run();
            assertThat(got.get()).isNotNull();
        }
        // Only the longpoll request should have hit the server — no preflight.
        assertThat(server.getRequestCount()).isEqualTo(1);
        RecordedRequest poll = server.takeRequest();
        assertThat(poll.getPath()).contains("category=" + expectedCategory);
    }

    @Test
    void longpoll_byRadioId_categoryMatchesTwoStepEquivalent() {
        // The int overload must produce the same wire-level category as the
        // explicit two-step `deriveLongpollCategory(radio_id) + longpoll(category)` form.
        String oneStep = audd.streams().deriveLongpollCategory(42);
        String twoStep = audd.streams().deriveLongpollCategory(42);
        assertThat(oneStep).isEqualTo(twoStep);
        assertThat(oneStep).hasSize(9);
    }

    @Test
    void longpoll_stringOverloadStillWorks() throws Exception {
        // Existing tokenless callers (pre-derived 9-char category) keep working.
        server.enqueue(new MockResponse().setBody(
            "{\"notification\":{\"radio_id\":3,\"notification_code\":650,\"notification_message\":\"x\"},"
            + "\"time\":1,\"timestamp\":1}"));
        try (LongpollPoll poll = audd.streams().longpoll("abc123def",
            LongpollOptions.builder().skipCallbackCheck(true).build())) {
            AtomicReference<StreamCallbackNotification> got = new AtomicReference<>();
            poll.onNotification(n -> { got.set(n); poll.close(); });
            poll.run();
            assertThat(got.get()).isNotNull();
        }
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("category=abc123def");
    }

    @Test
    void asyncLongpoll_byRadioId_derivesCategory() throws Exception {
        try (AsyncAudD a = AudD.builder()
                .apiToken("test")
                .apiBase(server.url("/").toString().replaceAll("/$", ""))
                .maxRetries(1)
                .backoffFactorMs(1)
                .buildAsync()) {
            // Single notification window — drives the loop and lets us close.
            server.enqueue(new MockResponse().setBody(
                "{\"notification\":{\"radio_id\":42,\"notification_code\":650,\"notification_message\":\"x\"},"
                + "\"time\":1,\"timestamp\":1}"));

            String expectedCategory = a.streams().deriveLongpollCategory(42);
            LongpollPoll poll = a.streams().longpoll(42,
                LongpollOptions.builder().skipCallbackCheck(true).build()).get(5, TimeUnit.SECONDS);
            try {
                AtomicReference<StreamCallbackNotification> got = new AtomicReference<>();
                poll.onNotification(n -> { got.set(n); poll.close(); });
                poll.run();
                assertThat(got.get()).isNotNull();
            } finally {
                poll.close();
            }
            RecordedRequest req = server.takeRequest();
            assertThat(req.getPath()).contains("category=" + expectedCategory);
        }
    }
}
