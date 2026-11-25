package io.audd;

import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDException;
import io.audd.errors.AudDServerError;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exception types on the async client, per-call timeouts, missing-file
 * sources, and the API token staying out of toString() and messages.
 */
class ClientBehaviourTest {
    private static final String TOKEN = "client-behaviour-secret-token";

    private MockWebServer server;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        base = server.url("/").toString().replaceAll("/$", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static String deadBase() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    @Test
    void async_networkFailure_isAnAudDConnectionError() throws Exception {
        List<AudDEvent> events = new CopyOnWriteArrayList<>();
        String dead = deadBase();
        try (AsyncAudD a = AudD.builder().apiToken(TOKEN).apiBase(dead).enterpriseBase(dead)
                .maxRetries(1).onEvent(events::add).buildAsync()) {
            assertThatThrownBy(() -> a.recognize("https://example.com/a.mp3").get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(AudDConnectionError.class)
                .isInstanceOf(AudDException.class)
                .hasCauseInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
            assertThatThrownBy(() -> a.recognizeEnterprise("https://example.com/a.mp3").get(10, TimeUnit.SECONDS))
                .cause().isInstanceOf(AudDConnectionError.class);
            assertThatThrownBy(() -> a.streams().list().get(10, TimeUnit.SECONDS))
                .cause().isInstanceOf(AudDConnectionError.class);
            assertThatThrownBy(() -> a.advanced().rawRequest("getStreams", null).get(10, TimeUnit.SECONDS))
                .cause().isInstanceOf(AudDConnectionError.class);
        }
        // The event names the network exception, as the sync client's does.
        AudDEvent last = events.get(1);
        assertThat(last.kind()).isEqualTo(AudDEvent.Kind.EXCEPTION);
        assertThat((String) last.extras().get("error_type")).isNotEqualTo("AudDConnectionError");
    }

    @Test
    void timeoutMs_cutsOffASlowRecognition_withoutRetrying() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}")
            .setHeadersDelay(1_500, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));
        try (AudD a = AudD.builder().apiToken(TOKEN).apiBase(base).maxRetries(3).backoffFactorMs(1).build()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> a.recognize("https://example.com/a.mp3",
                    RecognizeOptions.builder().timeoutMs(200L).build()))
                .isInstanceOf(AudDConnectionError.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_200);
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void timeoutMs_onEnterprise_cutsOffTheAsyncCall() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":[]}")
            .setHeadersDelay(1_500, TimeUnit.MILLISECONDS));
        try (AsyncAudD a = AudD.builder().apiToken(TOKEN).enterpriseBase(base).maxRetries(1).buildAsync()) {
            assertThatThrownBy(() -> a.recognizeEnterprise("https://example.com/a.mp3",
                    EnterpriseOptions.builder().limit(1).timeoutMs(200L).build()).get(1_200, TimeUnit.MILLISECONDS))
                .cause().isInstanceOf(AudDConnectionError.class);
        }
    }

    @Test
    void missingPathOrFile_isACallerError_andSendsNothing() {
        try (AudD a = AudD.builder().apiToken(TOKEN).apiBase(base).build()) {
            assertThatThrownBy(() -> a.recognize(Path.of("does-not-exist.mp3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does-not-exist.mp3");
            assertThatThrownBy(() -> a.recognize(new File("does-not-exist.mp3")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> a.customCatalog().add(1, Path.of("does-not-exist.mp3")))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void apiToken_staysOutOfToStringAndErrorMessages() throws Exception {
        AudD.Builder builder = AudD.builder().apiToken(TOKEN).apiBase(deadBase()).maxRetries(1);
        AudDOptions options = builder.buildOptions();
        assertThat(options.toString()).doesNotContain(TOKEN);
        try (AudD a = builder.build(); AsyncAudD b = builder.buildAsync()) {
            assertThat(a.toString()).doesNotContain(TOKEN);
            assertThat(b.toString()).doesNotContain(TOKEN);
            assertThat(a.streams().toString()).doesNotContain(TOKEN);
            assertThatThrownBy(() -> a.recognize("https://example.com/a.mp3"))
                .isInstanceOfSatisfying(AudDConnectionError.class, e -> {
                    assertThat(e.toString()).doesNotContain(TOKEN);
                    assertThat(String.valueOf(e.getCause())).doesNotContain(TOKEN);
                });
            assertThatThrownBy(() -> a.streams().list())
                .isInstanceOfSatisfying(AudDConnectionError.class,
                    e -> assertThat(e.toString()).doesNotContain(TOKEN));
        }
        // A server error that echoes the request keeps the echo out of the message.
        server.enqueue(new MockResponse().setBody("{\"status\":\"error\",\"error\":{\"error_code\":1000,"
            + "\"error_message\":\"Internal error\"},\"request_params\":{\"api_token\":\"" + TOKEN + "\"}}"));
        try (AudD a = AudD.builder().apiToken(TOKEN).apiBase(base).maxRetries(1).build()) {
            assertThatThrownBy(() -> a.recognize("https://example.com/a.mp3"))
                .isInstanceOfSatisfying(AudDServerError.class,
                    e -> assertThat(e.toString()).doesNotContain(TOKEN));
        }
    }
}
