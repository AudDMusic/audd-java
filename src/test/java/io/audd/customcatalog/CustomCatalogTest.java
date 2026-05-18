package io.audd.customcatalog;

import io.audd.AsyncAudD;
import io.audd.AudD;
import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDServerError;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * customCatalog.add must <strong>never</strong> retry by default. The endpoint
 * is metered: a silent re-upload after a transport hiccup could double-charge
 * the user. These tests pin the policy in place so a future "uniformity"
 * refactor can't accidentally roll it back.
 */
class CustomCatalogTest {
    private MockWebServer server;
    private AudD audd;
    private AsyncAudD asyncAudd;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // maxRetries=5 deliberately: we're verifying that customCatalog.add
        // ignores the configured retry budget and stays at 1 attempt.
        String base = server.url("/").toString().replaceAll("/$", "");
        audd = AudD.builder()
            .apiToken("test")
            .apiBase(base)
            .maxRetries(5)
            .backoffFactorMs(1)
            .build();
        asyncAudd = AudD.builder()
            .apiToken("test")
            .apiBase(base)
            .maxRetries(5)
            .backoffFactorMs(1)
            .buildAsync();
    }

    @AfterEach
    void tearDown() throws Exception {
        audd.close();
        asyncAudd.close();
        server.shutdown();
    }

    @Test
    void add_5xx_doesNotRetry_sync() throws Exception {
        // Server returns 502 with non-JSON body (matches the AudDTest pattern
        // that's known to surface as AudDServerError preserving status). If
        // retry kicked in we'd see >1 request.
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        // Queue a second OK in case of an erroneous retry — the test would
        // then succeed (no exception) and fail the request-count assertion.
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));

        assertThatThrownBy(() -> audd.customCatalog().add(123, "https://example.com/song.mp3"))
            .isInstanceOfSatisfying(AudDServerError.class, e -> assertThat(e.httpStatus()).isEqualTo(502));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void add_5xx_doesNotRetry_async() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":\"ok\"}"));

        assertThatThrownBy(() -> asyncAudd.customCatalog().add(123, "https://example.com/song.mp3").get())
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(AudDServerError.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void add_preUploadConnectError_doesNotRetry_sync() throws Exception {
        // Bind a port, close it, and point the client at the now-dead port.
        // The first POST will fail with a pre-upload ConnectException — which
        // a MUTATING policy <em>would</em> normally retry. A 1-attempt policy
        // must not.
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        try (AudD isolated = AudD.builder()
                .apiToken("test")
                .apiBase("http://127.0.0.1:" + deadPort)
                .maxRetries(5)
                .backoffFactorMs(1)
                .build()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> isolated.customCatalog().add(123, "https://example.com/song.mp3"))
                .isInstanceOf(AudDConnectionError.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            // 5 attempts with backoff would be measurable even at 1ms factor;
            // a single attempt should return promptly (well under 1s).
            assertThat(elapsedMs).isLessThan(2_000);
        }
    }

    @Test
    void add_preUploadConnectError_doesNotRetry_async() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        try (AsyncAudD isolated = AudD.builder()
                .apiToken("test")
                .apiBase("http://127.0.0.1:" + deadPort)
                .maxRetries(5)
                .backoffFactorMs(1)
                .buildAsync()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> isolated.customCatalog().add(123, "https://example.com/song.mp3").get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertThat(elapsedMs).isLessThan(2_000);
        }
    }
}
