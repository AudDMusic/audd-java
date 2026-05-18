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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin OkHttp wrapper that adds {@code api_token} to every request, parses
 * the response body as JSON, and surfaces an {@link HttpResponse} that the
 * upper layers can inspect uniformly.
 *
 * <p>If the caller passes an injected {@link OkHttpClient}, this class won't
 * own its lifecycle (won't shut it down on {@link #close()}).</p>
 */
public final class HttpClient implements AutoCloseable {
    public static final long DEFAULT_CONNECT_TIMEOUT_S = 30;
    public static final long DEFAULT_READ_TIMEOUT_S = 60;
    public static final long ENTERPRISE_READ_TIMEOUT_S = 3600;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<String> apiToken;
    private final OkHttpClient http;
    private final boolean ownedHttp;

    public HttpClient(String apiToken, OkHttpClient injected, long readTimeoutS) {
        this.apiToken = new AtomicReference<>(apiToken);
        this.ownedHttp = injected == null;
        this.http = injected != null ? injected : new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
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
        Request req = buildPostRequest(url, fields, fileBody);
        try (Response res = http.newCall(req).execute()) {
            return wrap(res);
        }
    }

    /** Async variant returning a {@link CompletableFuture}. */
    public CompletableFuture<HttpResponse> postFormAsync(String url, Map<String, String> fields, RequestBody fileBody) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        try {
            Request req = buildPostRequest(url, fields, fileBody);
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
