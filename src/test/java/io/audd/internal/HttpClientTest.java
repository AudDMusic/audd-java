package io.audd.internal;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientTest {
    private MockWebServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new HttpClient("test-token", null, 60);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    void postForm_addsApiTokenAndUserAgent() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setResponseCode(200));

        Map<String, String> fields = new HashMap<>();
        fields.put("url", "https://example.com/audio.mp3");
        HttpResponse res = client.postForm(server.url("/").toString(), fields, null);

        assertThat(res.httpStatus()).isEqualTo(200);
        assertThat(res.jsonBody()).isNotNull();
        assertThat(res.jsonBody().get("status").asText()).isEqualTo("success");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        String contentType = req.getHeader("Content-Type");
        assertThat(contentType).startsWith("multipart/form-data");
        assertThat(req.getHeader("User-Agent")).startsWith("audd-java/");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("api_token").contains("test-token");
        assertThat(body).contains("url").contains("https://example.com/audio.mp3");
    }

    @Test
    void postForm_includesFileBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setResponseCode(200));

        RequestBody file = RequestBody.create(new byte[]{1, 2, 3, 4, 5}, MediaType.parse("application/octet-stream"));
        client.postForm(server.url("/").toString(), new HashMap<>(), file);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        // multipart body should include a file field
        assertThat(body).contains("filename=\"upload.bin\"");
    }

    @Test
    void get_appendsApiTokenWhenMissing() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setResponseCode(200));

        Map<String, String> params = new HashMap<>();
        params.put("category", "abc");
        client.get(server.url("/longpoll").toString(), params);

        RecordedRequest req = server.takeRequest();
        String path = req.getPath();
        assertThat(path).contains("api_token=test-token");
        assertThat(path).contains("category=abc");
    }

    @Test
    void get_doesNotDuplicateApiTokenWhenAlreadyPresent() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setResponseCode(200));

        client.get(server.url("/longpoll?api_token=baked-in").toString(), new HashMap<>());

        RecordedRequest req = server.takeRequest();
        String path = req.getPath();
        // The query had baked-in token; client must not append a second.
        long count = path.chars().filter(c -> c == '&').count();
        assertThat(path).contains("api_token=baked-in");
        // expected query has no extra api_token entry
        assertThat(path).doesNotContain("api_token=test-token");
        // (suppress unused warning)
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    void postFormAsync_returnsCompletableFuture() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setResponseCode(200));

        HttpResponse res = client.postFormAsync(server.url("/").toString(), new HashMap<>(), null).get();
        assertThat(res.httpStatus()).isEqualTo(200);
    }

    @Test
    void wrap_preservesRawTextOnNonJsonBody() throws Exception {
        server.enqueue(new MockResponse().setBody("<html>oops</html>").setResponseCode(502));

        HttpResponse res = client.get(server.url("/").toString(), new HashMap<>());
        assertThat(res.httpStatus()).isEqualTo(502);
        assertThat(res.jsonBody()).isNull();
        assertThat(res.rawText()).contains("<html>oops</html>");
    }

    @Test
    void wrap_extractsRequestIdHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"status\":\"success\"}")
                .setResponseCode(200)
                .setHeader("X-Request-Id", "req-abc-123"));

        HttpResponse res = client.get(server.url("/").toString(), new HashMap<>());
        assertThat(res.requestId()).isEqualTo("req-abc-123");
    }

    @Test
    void newGetWithoutToken_sendsNoApiToken_evenWhenOneIsPassed() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"timeout\":\"no events before timeout\",\"timestamp\":1}"));

        Map<String, String> params = new HashMap<>();
        params.put("category", "abc");
        params.put("api_token", "test-token");
        Call call = client.newGetWithoutToken(server.url("/longpoll/?api_token=baked-in").toString(), params, 65);
        HttpResponse res = client.execute(call);
        assertThat(res.httpStatus()).isEqualTo(200);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("category=abc").doesNotContain("api_token");
        assertThat(req.getHeaders().toString()).doesNotContain("test-token");
        assertThat(req.getHeader("User-Agent")).startsWith("audd-java/");
    }

    @Test
    void injectedClient_keepsInterceptors_andGetsTheSdkTimeouts() throws Exception {
        List<Integer> readTimeouts = new CopyOnWriteArrayList<>();
        List<Integer> writeTimeouts = new CopyOnWriteArrayList<>();
        OkHttpClient injected = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    readTimeouts.add(chain.readTimeoutMillis());
                    writeTimeouts.add(chain.writeTimeoutMillis());
                    return chain.proceed(chain.request().newBuilder().header("X-Injected", "yes").build());
                })
                .build();
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}"));
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}"));
        try (HttpClient enterprise = new HttpClient("t", injected, 3600)) {
            enterprise.postForm(server.url("/").toString(), new HashMap<>(), null);
            enterprise.postForm(server.url("/").toString(), new HashMap<>(), null, 1_500L);
        }
        assertThat(readTimeouts).containsExactly(3_600_000, 1_500);
        assertThat(writeTimeouts).containsExactly(3_600_000, 1_500);
        assertThat(server.takeRequest().getHeader("X-Injected")).isEqualTo("yes");
        // The injected client is the caller's: closing the SDK wrapper leaves it usable.
        assertThat(injected.dispatcher().executorService().isShutdown()).isFalse();
    }

    @Test
    void postForm_perRequestTimeout_capsTheWholeRequest() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"success\"}").setHeadersDelay(1_500, TimeUnit.MILLISECONDS));
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.postForm(server.url("/").toString(), new HashMap<>(), null, 200L))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_200);
    }
}
