package io.audd.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.AudD;
import io.audd.errors.AudDAuthenticationError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDServerError;
import io.audd.internal.HttpResponse;
import io.audd.models.StreamCallbackMatch;
import io.audd.models.StreamCallbackNotification;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The longpoll consumer against the three response shapes the server sends
 * ({@code events}, {@code timeout}, {@code error}), the top-level callback
 * shape, junk bodies, retries and close().
 */
class LongpollPollTest {
    private static final String TOKEN = "longpoll-test-token-not-on-the-wire";
    private static final LongpollOptions SKIP_PREFLIGHT =
        LongpollOptions.builder().skipCallbackCheck(true).build();

    private MockWebServer server;
    private Script script;
    private AudD audd;

    private final List<StreamCallbackMatch> matches = new CopyOnWriteArrayList<>();
    private final List<StreamCallbackNotification> notifications = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();

    /** Serves queued responses in order, then {@link #fallback} for every later request. */
    static final class Script extends Dispatcher {
        final BlockingQueue<MockResponse> queue = new LinkedBlockingQueue<>();
        volatile MockResponse fallback = new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);

        Script enqueue(MockResponse r) { queue.add(r); return this; }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            MockResponse next = queue.poll();
            return next != null ? next : fallback;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        script = new Script();
        server.setDispatcher(script);
        server.start();
        audd = client(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        audd.close();
        server.shutdown();
    }

    private AudD client(int maxRetries) {
        return AudD.builder()
            .apiToken(TOKEN)
            .apiBase(server.url("/").toString().replaceAll("/$", ""))
            .maxRetries(maxRetries)
            .backoffFactorMs(1)
            .build();
    }

    private LongpollPoll poll(AudD client, LongpollOptions opts) {
        return client.streams().longpoll("92b1cc7f0", opts)
            .onMatch(matches::add)
            .onNotification(notifications::add)
            .onError(errors::add);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = LongpollPollTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private RecordedRequest nextRequest() throws InterruptedException {
        RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(r).as("expected another longpoll request").isNotNull();
        return r;
    }

    private static void assertNoToken(RecordedRequest r) {
        assertThat(r.getPath()).startsWith("/longpoll/").doesNotContain("api_token").doesNotContain(TOKEN);
        assertThat(r.getHeaders().toString()).doesNotContain(TOKEN);
        assertThat(r.getBody().readUtf8()).doesNotContain(TOKEN);
    }

    @Test
    void eventsFixture_deliversBothSongsInOrder_thenAsksForNewerEvents() throws Exception {
        script.enqueue(json(fixture("longpoll_events.json")));
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();

        RecordedRequest first = nextRequest();
        RecordedRequest second = nextRequest();
        poll.close();
        done.get(5, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).radioId()).isEqualTo(7L);
        assertThat(matches.get(0).song().artist()).isEqualTo("Alan Walker, A$AP Rocky");
        assertThat(matches.get(0).song().title()).isEqualTo("Live Fast (PUBGM)");
        assertThat(matches.get(1).radioId()).isEqualTo(7L);
        assertThat(matches.get(1).song().artist()).isEqualTo("Tears For Fears");
        assertThat(matches.get(1).song().title()).isEqualTo("Everybody Wants To Rule The World");

        assertThat(first.getPath()).contains("category=92b1cc7f0").contains("timeout=50")
            .doesNotContain("since_time");
        assertThat(second.getPath()).contains("since_time=1795547548112");
        assertNoToken(first);
        assertNoToken(second);
    }

    @Test
    void timeoutFixture_deliversNothing_andSendsItsTimestamp() throws Exception {
        script.enqueue(json(fixture("longpoll_no_events.json")));
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();

        nextRequest();
        RecordedRequest second = nextRequest();
        poll.close();
        done.get(5, TimeUnit.SECONDS);

        assertThat(matches).isEmpty();
        assertThat(notifications).isEmpty();
        assertThat(errors).isEmpty();
        assertThat(second.getPath()).contains("since_time=1777901270049");
    }

    @Test
    void errorFixture_reachesOnErrorOnce_afterExactlyOneRequest() throws Exception {
        script.enqueue(json(fixture("longpoll_error.json")));
        // Anything after the error would be a bug: answer it like the server does.
        script.fallback = json(fixture("longpoll_error.json"));
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);

        poll.runAsync().get(5, TimeUnit.SECONDS);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOfSatisfying(AudDInvalidRequestError.class, e ->
            assertThat(e.serverMessage()).isEqualTo("Invalid timeout arg.  Must be 1-120."));
        assertThat(matches).isEmpty();
    }

    @Test
    void topLevelCallbackShape_stillDelivers_andAdvancesSinceTime() throws Exception {
        script.enqueue(json(
            "{\"status\":\"success\",\"result\":{\"radio_id\":3,\"timestamp\":\"2020-04-13 10:31:43\","
            + "\"results\":[{\"artist\":\"a\",\"title\":\"t\"}]},\"timestamp\":42}"));
        script.enqueue(json(
            "{\"notification\":{\"radio_id\":3,\"notification_code\":650,\"notification_message\":\"x\"},"
            + "\"time\":1587939136}"));
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();

        nextRequest();
        RecordedRequest second = nextRequest();
        RecordedRequest third = nextRequest();
        poll.close();
        done.get(5, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).song().artist()).isEqualTo("a");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).notificationCode()).isEqualTo(650);
        assertThat(second.getPath()).contains("since_time=42");
        assertThat(third.getPath()).contains("since_time=42");
    }

    @Test
    void junkBody_withDefaultBackoff_waitsBetweenRequests() throws Exception {
        script.fallback = new MockResponse().setBody("<html>not json</html>");
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();

        // Requests at about 0 s, 1 s and 3 s: at most 3 in a 2.5 s window.
        Thread.sleep(2_500);
        int requests = server.getRequestCount();
        assertThat(requests).isBetween(1, 3);
        assertThat(done).isNotDone();
        assertThat(errors).isEmpty();

        long closedAt = System.nanoTime();
        poll.close();
        done.get(2, TimeUnit.SECONDS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt)).isLessThan(1_000);
    }

    @Test
    void unknownShapesAndEmptyBodies_backOffUpToTheCap() throws Exception {
        BlockingQueue<MockResponse> junk = script.queue;
        junk.add(new MockResponse().setBody(""));
        junk.add(json("{\"unexpected\":true}"));
        junk.add(json("[1,2,3]"));
        script.fallback = json("{\"something\":\"else\",\"timestamp\":\"soon\"}");
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT).idleBackoff(20, 80);
        CompletableFuture<Void> done = poll.runAsync();

        // Pauses of 20, 40, 80, 80, ... ms: well under 20 requests in 600 ms.
        Thread.sleep(600);
        poll.close();
        done.get(2, TimeUnit.SECONDS);
        assertThat(server.getRequestCount()).isBetween(4, 15);
        assertThat(errors).isEmpty();
        assertThat(poll.idleDelayMs(0)).isEqualTo(20);
        assertThat(poll.idleDelayMs(10)).isEqualTo(80);
    }

    @Test
    void defaultIdleBackoff_startsAtOneSecondAndCapsAtThirty() {
        LongpollPoll poll = audd.streams().longpoll("abc", SKIP_PREFLIGHT);
        assertThat(poll.idleDelayMs(0)).isEqualTo(1_000);
        assertThat(poll.idleDelayMs(1)).isEqualTo(2_000);
        assertThat(poll.idleDelayMs(4)).isEqualTo(16_000);
        assertThat(poll.idleDelayMs(5)).isEqualTo(30_000);
        assertThat(poll.idleDelayMs(50)).isEqualTo(30_000);
    }

    @Test
    void emptyEventsList_isNothingNew_andKeepsSinceTime() throws Exception {
        script.fallback = json("{\"events\":[]}");
        LongpollPoll poll = poll(audd, LongpollOptions.builder()
            .skipCallbackCheck(true).sinceTime(123L).build()).idleBackoff(50, 50);
        CompletableFuture<Void> done = poll.runAsync();

        Thread.sleep(400);
        poll.close();
        done.get(2, TimeUnit.SECONDS);

        int requests = server.getRequestCount();
        assertThat(requests).isBetween(2, 12);
        for (int i = 0; i < requests; i++) {
            assertThat(nextRequest().getPath()).contains("since_time=123");
        }
        assertThat(errors).isEmpty();
    }

    @Test
    void badAndEmptyEvents_areSkipped_andTheRestDeliver() throws Exception {
        script.enqueue(json("{\"events\":["
            + "{\"timestamp\":10,\"data\":\"not json at all\"},"
            + "{\"timestamp\":11,\"data\":{\"status\":\"success\",\"result\":{\"radio_id\":1,\"results\":[]}}},"
            + "{\"timestamp\":12,\"data\":\"{\\\"status\\\":\\\"success\\\",\\\"result\\\":{\\\"radio_id\\\":2,"
            + "\\\"results\\\":[{\\\"artist\\\":\\\"A\\\",\\\"title\\\":\\\"T\\\"}]}}\"},"
            + "{\"timestamp\":13,\"data\":{\"status\":\"-\",\"notification\":{\"radio_id\":2,"
            + "\"notification_code\":650,\"notification_message\":\"can't connect\"},\"time\":1587939136}},"
            + "{\"timestamp\":9,\"data\":[\"not\",\"an\",\"object\"]},"
            + "\"not an event\""
            + "]}"));
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();

        nextRequest();
        RecordedRequest second = nextRequest();
        poll.close();
        done.get(5, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).radioId()).isEqualTo(2L);
        assertThat(matches.get(0).song().artist()).isEqualTo("A");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).notificationMessage()).isEqualTo("can't connect");
        assertThat(second.getPath()).contains("since_time=13");
    }

    @Test
    void close_cancelsTheWaitingRequest() throws Exception {
        // NO_RESPONSE: the server reads the request and never answers.
        LongpollPoll poll = poll(audd, SKIP_PREFLIGHT);
        CompletableFuture<Void> done = poll.runAsync();
        nextRequest();

        long closedAt = System.nanoTime();
        poll.close();
        done.get(2, TimeUnit.SECONDS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAt)).isLessThan(1_000);
        assertThat(errors).isEmpty();
        assertThat(poll.isClosed()).isTrue();
    }

    @Test
    void serverErrors_areRetried_andTheSubscriptionContinues() throws Exception {
        try (AudD retrying = client(3)) {
            script.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
            script.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
            script.enqueue(json(fixture("longpoll_events.json")));
            LongpollPoll poll = poll(retrying, SKIP_PREFLIGHT);
            CompletableFuture<Void> done = poll.runAsync();

            nextRequest();
            nextRequest();
            nextRequest();
            RecordedRequest afterEvents = nextRequest();
            poll.close();
            done.get(5, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(matches).hasSize(2);
            assertThat(afterEvents.getPath()).contains("since_time=1795547548112");
        }
    }

    @Test
    void serverErrors_beyondMaxRetries_reachOnError() throws Exception {
        try (AudD retrying = client(2)) {
            script.fallback = new MockResponse().setResponseCode(503).setBody("busy");
            LongpollPoll poll = poll(retrying, SKIP_PREFLIGHT);
            poll.runAsync().get(5, TimeUnit.SECONDS);

            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOfSatisfying(AudDServerError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(503));
        }
    }

    @Test
    void readTimeout_coversThePollWindow_notTheStandardTimeout() throws Exception {
        try (AudD shortTimeout = AudD.builder()
                .apiToken(TOKEN)
                .apiBase(server.url("/").toString().replaceAll("/$", ""))
                .maxRetries(1)
                .standardTimeoutSeconds(1)
                .build()) {
            // The server holds the request for 2 s, longer than the 1 s standard timeout.
            script.enqueue(json(fixture("longpoll_no_events.json")).setHeadersDelay(2, TimeUnit.SECONDS));
            LongpollPoll poll = poll(shortTimeout, LongpollOptions.builder()
                .skipCallbackCheck(true).timeout(1).build());
            CompletableFuture<Void> done = poll.runAsync();

            nextRequest();
            RecordedRequest second = nextRequest();
            poll.close();
            done.get(5, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(second.getPath()).contains("since_time=1777901270049").contains("timeout=1");
        }
    }

    @Test
    void interpret_standardErrorBody_isTypedByErrorCode() throws Exception {
        ObjectMapper m = new ObjectMapper();
        String body = "{\"status\":\"error\",\"error\":{\"error_code\":900,\"error_message\":\"bad token\"}}";
        LongpollPoll.Page page = LongpollPoll.interpret(new HttpResponse(m.readTree(body), 200, null, body));
        assertThat(page.error).isInstanceOf(AudDAuthenticationError.class);
    }

    @Test
    void interpret_timestampsAsStrings_areAccepted() throws Exception {
        ObjectMapper m = new ObjectMapper();
        String body = "{\"timeout\":\"no events before timeout\",\"timestamp\":\"1777901270049\"}";
        LongpollPoll.Page page = LongpollPoll.interpret(new HttpResponse(m.readTree(body), 200, null, body));
        assertThat(page.error).isNull();
        assertThat(page.since).isEqualTo(1777901270049L);
    }
}
