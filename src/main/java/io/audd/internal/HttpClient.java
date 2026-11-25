package io.audd.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Thin OkHttp wrapper that adds {@code api_token} to authenticated requests,
 * parses the response body as JSON, and surfaces an {@link HttpResponse} that
 * the upper layers can inspect uniformly. Longpoll requests go through
 * {@link #newGetWithoutToken}, which never carries the token.
 *
 * <p>If the caller passes an injected {@link OkHttpClient}, this class won't
 * own its lifecycle (won't shut it down on {@link #close()}). Requests reuse
 * the injected client's connection pool, dispatcher, proxy and interceptors,
 * with the read and write timeouts given to the constructor.</p>
 */
public final class HttpClient implements AutoCloseable {
    public static final long DEFAULT_CONNECT_TIMEOUT_S = 30;
    public static final long DEFAULT_READ_TIMEOUT_S = 60;
    public static final long ENTERPRISE_READ_TIMEOUT_S = 3600;

    private static final ObjectMapper MAPPER = Json.mapper();
    private static final int MAX_TIMEOUT_VARIANTS = 16;

    private final AtomicReference<String> apiToken;
    private final OkHttpClient http;
    private final boolean ownedHttp;
    /** Clients derived from {@link #http} with a per-request timeout, keyed by milliseconds. */
    private final ConcurrentMap<Long, OkHttpClient> callTimeoutVariants = new ConcurrentHashMap<>();
    /** Clients derived from {@link #http} with another read timeout only, keyed by milliseconds. */
    private final ConcurrentMap<Long, OkHttpClient> readTimeoutVariants = new ConcurrentHashMap<>();

    public HttpClient(String apiToken, OkHttpClient injected, long readTimeoutS) {
        this.apiToken = new AtomicReference<>(apiToken);
        this.ownedHttp = injected == null;
        OkHttpClient.Builder builder = injected != null
                ? injected.newBuilder()
                : new OkHttpClient.Builder().connectTimeout(DEFAULT_CONNECT_TIMEOUT_S, TimeUnit.SECONDS);
        this.http = builder
                .readTimeout(readTimeoutS, TimeUnit.SECONDS)
                .writeTimeout(readTimeoutS, TimeUnit.SECONDS)
                .build();
    }

    public String apiToken() { return apiToken.get(); }

    /**
     * Atomically rotate the api_token used for subsequent requests. In-flight
     * requests continue with the previous token (no abort). Spec §7.10.
     */
    public void setApiToken(String newToken) {
        apiToken.set(newToken);
    }

    /**
     * POST a multipart/form-data request. {@code fileBody} is the file body
     * (or {@code null} when the source is a URL passed via {@code url=...}
     * field). {@code apiToken} is always added.
     */
    public HttpResponse postForm(String url, Map<String, String> fields, RequestBody fileBody) throws IOException {
        return postForm(url, fields, fileBody, null);
    }

    /**
     * {@link #postForm(String, Map, RequestBody)} with a timeout for this one
     * request. A positive {@code timeoutMs} replaces the read and write
     * timeouts and also caps the whole request (connect, upload and response)
     * at that many milliseconds; {@code null} keeps the client's timeouts.
     */
    public HttpResponse postForm(String url, Map<String, String> fields, RequestBody fileBody, Long timeoutMs)
            throws IOException {
        Request req = buildPostRequest(url, fields, fileBody);
        try (Response res = newCall(req, timeoutMs).execute()) {
            return wrap(res);
        }
    }

    /** Async variant returning a {@link CompletableFuture}. */
    public CompletableFuture<HttpResponse> postFormAsync(String url, Map<String, String> fields, RequestBody fileBody) {
        return postFormAsync(url, fields, fileBody, null);
    }

    /** Async variant of {@link #postForm(String, Map, RequestBody, Long)}. */
    public CompletableFuture<HttpResponse> postFormAsync(String url, Map<String, String> fields, RequestBody fileBody,
                                                         Long timeoutMs) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        try {
            Request req = buildPostRequest(url, fields, fileBody);
            newCall(req, timeoutMs).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { future.completeExceptionally(e); }
                @Override public void onResponse(Call call, Response response) {
                    try (Response r = response) { future.complete(wrap(r)); }
                    catch (Throwable t) { future.completeExceptionally(t); }
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /** GET with query params. {@code api_token} is added unless already present. */
    public HttpResponse get(String url, Map<String, String> params) throws IOException {
        Request req = buildGetRequest(url, params);
        try (Response res = http.newCall(req).execute()) {
            return wrap(res);
        }
    }

    public CompletableFuture<HttpResponse> getAsync(String url, Map<String, String> params) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        try {
            Request req = buildGetRequest(url, params);
            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { future.completeExceptionally(e); }
                @Override public void onResponse(Call call, Response response) {
                    try (Response r = response) { future.complete(wrap(r)); }
                    catch (Throwable t) { future.completeExceptionally(t); }
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * Prepare a GET that carries no {@code api_token}: an {@code api_token}
     * entry in {@code params} is dropped. Used for longpoll, which the
     * category alone authorizes. {@code readTimeoutS} replaces the client's
     * read timeout for this request. The returned call has not started: run
     * it with {@link #execute(Call)}; {@link Call#cancel()} stops it from any
     * thread.
     */
    public Call newGetWithoutToken(String url, Map<String, String> params, long readTimeoutS) {
        HttpUrl base = HttpUrl.parse(url);
        if (base == null) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        HttpUrl.Builder b = base.newBuilder().removeAllQueryParameters("api_token");
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null || "api_token".equals(e.getKey())) continue;
                b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request req = new Request.Builder()
                .url(b.build())
                .get()
                .header("User-Agent", UserAgent.value())
                .build();
        long readTimeoutMs = TimeUnit.SECONDS.toMillis(readTimeoutS);
        return derived(readTimeoutVariants, readTimeoutMs,
            cb -> cb.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)).newCall(req);
    }

    /** Execute a call made by {@link #newGetWithoutToken} and wrap its response. */
    public HttpResponse execute(Call call) throws IOException {
        try (Response res = call.execute()) {
            return wrap(res);
        }
    }

    private Call newCall(Request req, Long timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return http.newCall(req);
        }
        long ms = timeoutMs;
        return derived(callTimeoutVariants, ms, cb -> cb
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .writeTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms, TimeUnit.MILLISECONDS)).newCall(req);
    }

    /**
     * A client derived from {@link #http} by {@code configure}, cached in
     * {@code cache} under {@code key}. Derived clients share the connection
     * pool and dispatcher, so they are cheap; the cache only saves building
     * one per request.
     */
    private OkHttpClient derived(ConcurrentMap<Long, OkHttpClient> cache, long key,
                                 UnaryOperator<OkHttpClient.Builder> configure) {
        OkHttpClient cached = cache.get(key);
        if (cached != null) return cached;
        OkHttpClient built = configure.apply(http.newBuilder()).build();
        if (cache.size() < MAX_TIMEOUT_VARIANTS) {
            OkHttpClient raced = cache.putIfAbsent(key, built);
            if (raced != null) return raced;
        }
        return built;
    }

    private Request buildPostRequest(String url, Map<String, String> fields, RequestBody fileBody) {
        MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
        boolean hasToken = false;
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getValue() == null) continue;
                if ("api_token".equals(e.getKey())) hasToken = true;
                mb.addFormDataPart(e.getKey(), e.getValue());
            }
        }
        if (!hasToken) {
            mb.addFormDataPart("api_token", apiToken.get());
        }
        if (fileBody != null) {
            mb.addFormDataPart("file", "upload.bin", fileBody);
        }
        return new Request.Builder()
                .url(url)
                .post(mb.build())
                .header("User-Agent", UserAgent.value())
                .build();
    }

    private Request buildGetRequest(String url, Map<String, String> params) {
        HttpUrl base = HttpUrl.parse(url);
        if (base == null) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
        HttpUrl.Builder b = base.newBuilder();
        boolean hasToken = base.queryParameter("api_token") != null;
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null) continue;
                if ("api_token".equals(e.getKey())) hasToken = true;
                b.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        if (!hasToken) {
            b.addQueryParameter("api_token", apiToken.get());
        }
        return new Request.Builder()
                .url(b.build())
                .get()
                .header("User-Agent", UserAgent.value())
                .build();
    }

    private HttpResponse wrap(Response res) throws IOException {
        ResponseBody rb = res.body();
        String text = rb != null ? rb.string() : "";
        JsonNode body = null;
        if (text != null && !text.isEmpty()) {
            try {
                body = MAPPER.readTree(text);
            } catch (IOException e) {
                body = null;
            }
        }
        String requestId = res.header("X-Request-Id");
        if (requestId == null) requestId = res.header("x-request-id");
        return new HttpResponse(body, res.code(), requestId, text);
    }

    @Override
    public void close() {
        if (ownedHttp) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }
}
