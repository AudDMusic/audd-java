package io.audd;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.audd.models.RecognitionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AudDOptions.Builder#onEvent(java.util.function.Consumer)} inspection
 * hook: emits {@code REQUEST}/{@code RESPONSE}/{@code EXCEPTION} events,
 * never leaks tokens or bodies, swallows hook exceptions. See design spec §7.7a.
 */
class EventHookTest {

    @Test
    void onEvent_emitsRequestThenResponseForSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setBody("{\"status\":\"success\",\"result\":null}")
                    .setHeader("X-Request-Id", "req-abc"));

            List<AudDEvent> events = new CopyOnWriteArrayList<>();
            try (AudD a = AudD.builder()
                    .apiToken("test-token")
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .onEvent(events::add)
                    .build()) {
                a.recognize("https://example.com/a.mp3");
            }

            assertThat(events).hasSize(2);
            assertThat(events.get(0).kind()).isEqualTo(AudDEvent.Kind.REQUEST);
            assertThat(events.get(0).method()).isEqualTo("recognize");
            assertThat(events.get(0).httpStatus()).isNull();
            assertThat(events.get(0).elapsedMs()).isNull();

            assertThat(events.get(1).kind()).isEqualTo(AudDEvent.Kind.RESPONSE);
            assertThat(events.get(1).httpStatus()).isEqualTo(200);
            assertThat(events.get(1).requestId()).isEqualTo("req-abc");
            assertThat(events.get(1).elapsedMs()).isNotNull();
            assertThat(events.get(1).elapsedMs()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    void onEvent_neverContainsApiTokenOrBody() throws Exception {
        String secret = "ultra-secret-token-do-not-leak";
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));

            List<AudDEvent> events = new CopyOnWriteArrayList<>();
            try (AudD a = AudD.builder()
                    .apiToken(secret)
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .onEvent(events::add)
                    .build()) {
                a.recognize("https://example.com/a.mp3");
            }

            for (AudDEvent ev : events) {
                String full = ev.toString();
                assertThat(full).doesNotContain(secret);
                for (Object v : ev.extras().values()) {
                    assertThat(String.valueOf(v)).doesNotContain(secret);
                }
            }
        }
    }

    @Test
    void onEvent_hookExceptionIsSwallowed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));

            AtomicInteger called = new AtomicInteger();
            try (AudD a = AudD.builder()
                    .apiToken("t")
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .onEvent(ev -> {
                        called.incrementAndGet();
                        throw new RuntimeException("boom");
                    })
                    .build()) {
                // Must not propagate the hook exception.
                RecognitionResult r = a.recognize("https://example.com/a.mp3");
                assertThat(r).isNull();
            }
            assertThat(called.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void onEvent_emitsExceptionKindOnConnectionFailure() throws Exception {
        // Point at a closed server to force a connection error.
        MockWebServer server = new MockWebServer();
        server.start();
        String base = server.url("/").toString().replaceAll("/$", "");
        server.shutdown();

        List<AudDEvent> events = new CopyOnWriteArrayList<>();
        try (AudD a = AudD.builder()
                .apiToken("t")
                .apiBase(base)
                .maxRetries(1).backoffFactorMs(1)
                .onEvent(events::add)
                .build()) {
            assertThatThrownBy(() -> a.recognize("https://example.com/a.mp3"))
                    .isInstanceOf(io.audd.errors.AudDConnectionError.class);
        }

        // Should see at least a REQUEST + an EXCEPTION.
        List<AudDEvent.Kind> kinds = new ArrayList<>();
        for (AudDEvent e : events) kinds.add(e.kind());
        assertThat(kinds).contains(AudDEvent.Kind.REQUEST, AudDEvent.Kind.EXCEPTION);

        AudDEvent last = events.get(events.size() - 1);
        assertThat(last.kind()).isEqualTo(AudDEvent.Kind.EXCEPTION);
        assertThat(last.elapsedMs()).isNotNull();
    }

    @Test
    void async_onEvent_emitsRequestThenResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setBody("{\"status\":\"success\",\"result\":null}")
                    .setHeader("X-Request-Id", "req-async"));

            List<AudDEvent> events = new CopyOnWriteArrayList<>();
            try (AsyncAudD a = AudD.builder()
                    .apiToken("t")
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .onEvent(events::add)
                    .buildAsync()) {
                a.recognize("https://example.com/a.mp3").get();
            }

            assertThat(events).hasSize(2);
            assertThat(events.get(0).kind()).isEqualTo(AudDEvent.Kind.REQUEST);
            assertThat(events.get(1).kind()).isEqualTo(AudDEvent.Kind.RESPONSE);
            assertThat(events.get(1).requestId()).isEqualTo("req-async");
        }
    }
}
