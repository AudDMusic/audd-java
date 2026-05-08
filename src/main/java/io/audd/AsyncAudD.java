package io.audd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.advanced.AsyncAdvanced;
import io.audd.customcatalog.AsyncCustomCatalog;
import io.audd.internal.HttpClient;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryClass;
import io.audd.internal.RetryPolicy;
import io.audd.internal.SourcePreparer;
import io.audd.models.EnterpriseChunkResult;
import io.audd.models.EnterpriseMatch;
import io.audd.models.RecognitionResult;
import io.audd.streams.AsyncStreams;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Async client for the AudD music recognition API. Returns {@link CompletableFuture}
 * from every method that hits the network. Same configuration knobs as
 * {@link AudD}; safe with try-with-resources.
 */
public final class AsyncAudD implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final HttpClient enterpriseHttp;
    private final RetryPolicy readPolicy;
    private final RetryPolicy recognitionPolicy;
    private final RetryPolicy mutatingPolicy;
    private final Consumer<String> onDeprecation;
    private final Consumer<AudDEvent> onEvent;
    private final AtomicReference<String> apiToken;
    private final String apiBase;
    private final String enterpriseBase;

    private AsyncStreams streams;
    private AsyncCustomCatalog customCatalog;
    private AsyncAdvanced advanced;

    public AsyncAudD(String apiToken) {
        this(AudD.builder().apiToken(apiToken).buildOptions());
    }

    /**
     * Build an {@link AsyncAudD} using the {@value AudD#API_TOKEN_ENV_VAR}
     * environment variable. Throws {@link IllegalArgumentException} with a
     * dashboard-link hint when the variable is unset or empty. See design
     * spec §7.11.
     */
    public static AsyncAudD fromEnvironment() {
        return AudD.builder().buildAsync();
    }

    AsyncAudD(AudDOptions opts) {
        this.apiToken = new AtomicReference<>(opts.apiToken());
        this.http = new HttpClient(opts.apiToken(), opts.httpClient(), opts.standardTimeoutSeconds());
        this.enterpriseHttp = new HttpClient(opts.apiToken(), opts.httpClient(), opts.enterpriseTimeoutSeconds());
        this.readPolicy = new RetryPolicy(RetryClass.READ, opts.maxRetries(), opts.backoffFactorMs());
        this.recognitionPolicy = new RetryPolicy(RetryClass.RECOGNITION, opts.maxRetries(), opts.backoffFactorMs());
        this.mutatingPolicy = new RetryPolicy(RetryClass.MUTATING, opts.maxRetries(), opts.backoffFactorMs());
        this.onDeprecation = opts.onDeprecation();
        this.onEvent = opts.onEvent();
        this.apiBase = opts.apiBase() != null ? opts.apiBase() : AudD.API_BASE_DEFAULT;
        this.enterpriseBase = opts.enterpriseBase() != null ? opts.enterpriseBase() : AudD.ENTERPRISE_BASE_DEFAULT;
    }

    public CompletableFuture<RecognitionResult> recognize(Object source) {
        return recognize(source, RecognizeOptions.defaults());
    }

    public CompletableFuture<RecognitionResult> recognize(Object source, RecognizeOptions opts) {
        SourcePreparer.Prepared prepared = AudD.prepareSource(source);
        Map<String, String> base = new HashMap<>();
        if (opts != null && opts.returnMetadata() != null) base.put("return", String.join(",", opts.returnMetadata()));
        if (opts != null && opts.market() != null) base.put("market", opts.market());

        String url = apiBase + "/";
        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.REQUEST).method("recognize").url(url).build());
        long started = System.nanoTime();

        return recognitionPolicy.runAsync(() -> doAsyncCall(http, url, base, prepared))
                .whenComplete((resp, exc) -> {
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    if (exc != null) {
                        Map<String, Object> extras = new HashMap<>();
                        extras.put("error_type", unwrap(exc).getClass().getSimpleName());
                        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.EXCEPTION).method("recognize")
                                .url(url).elapsedMs(elapsed).extras(extras).build());
                    } else if (resp != null) {
                        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.RESPONSE).method("recognize")
                                .url(url).requestId(resp.requestId()).httpStatus(resp.httpStatus())
                                .elapsedMs(elapsed).build());
                    }
                })
                .thenApply(resp -> {
                    JsonNode body = ResponseDecoder.decodeOrRaise(resp, onDeprecation, false);
                    JsonNode result = body.path("result");
                    if (result.isMissingNode() || result.isNull()) return null;
                    try {
                        RecognitionResult r = MAPPER.treeToValue(result, RecognitionResult.class);
                        r.setRawResponse(result);
                        return r;
                    } catch (Exception e) {
                        throw new io.audd.errors.AudDSerializationError("Failed to decode RecognitionResult", resp.rawText());
                    }
                });
    }

    public CompletableFuture<List<EnterpriseMatch>> recognizeEnterprise(Object source) {
        return recognizeEnterprise(source, EnterpriseOptions.defaults());
    }

    public CompletableFuture<List<EnterpriseMatch>> recognizeEnterprise(Object source, EnterpriseOptions opts) {
        SourcePreparer.Prepared prepared = AudD.prepareSource(source);
        Map<String, String> base = AudD.buildEnterpriseFields(opts);

        String url = enterpriseBase + "/";
        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.REQUEST).method("recognize").url(url).build());
        long started = System.nanoTime();

        return recognitionPolicy.runAsync(() -> doAsyncCall(enterpriseHttp, url, base, prepared))
                .whenComplete((resp, exc) -> {
                    long elapsed = (System.nanoTime() - started) / 1_000_000L;
                    if (exc != null) {
                        Map<String, Object> extras = new HashMap<>();
                        extras.put("error_type", unwrap(exc).getClass().getSimpleName());
                        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.EXCEPTION).method("recognize")
                                .url(url).elapsedMs(elapsed).extras(extras).build());
                    } else if (resp != null) {
                        AudD.safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.RESPONSE).method("recognize")
                                .url(url).requestId(resp.requestId()).httpStatus(resp.httpStatus())
                                .elapsedMs(elapsed).build());
                    }
                })
                .thenApply(resp -> {
                    JsonNode body = ResponseDecoder.decodeOrRaise(resp, onDeprecation, false);
                    JsonNode result = body.path("result");
                    List<EnterpriseMatch> out = new ArrayList<>();
                    if (result == null || !result.isArray()) return out;
                    for (JsonNode chunkNode : result) {
                        try {
                            EnterpriseChunkResult chunk = MAPPER.treeToValue(chunkNode, EnterpriseChunkResult.class);
                            if (chunk != null && chunk.songs() != null) out.addAll(chunk.songs());
                        } catch (Exception e) {
                            throw new io.audd.errors.AudDSerializationError("Failed to decode EnterpriseChunkResult", resp.rawText());
                        }
                    }
                    return out;
                });
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }

    public AsyncStreams streams() {
        if (streams == null) streams = new AsyncStreams(http, readPolicy, mutatingPolicy, apiToken::get, onDeprecation, apiBase);
        return streams;
    }

    /**
     * Currently in-effect api_token (after any rotation via
     * {@link #setApiToken(String)}). Reads are non-blocking.
     */
    public String apiToken() {
        return apiToken.get();
    }

    /**
     * Atomically rotate the api_token used for subsequent requests. In-flight
     * requests continue with the old token. Thread-safe — safe to call
     * concurrently with {@link #recognize(Object)} and friends.
     *
     * @throws IllegalArgumentException if {@code newToken} is null or empty
     */
    public void setApiToken(String newToken) {
        if (newToken == null || newToken.isEmpty()) {
            throw new IllegalArgumentException("newToken must be a non-empty string");
        }
        apiToken.set(newToken);
        http.setApiToken(newToken);
        enterpriseHttp.setApiToken(newToken);
    }

    public AsyncCustomCatalog customCatalog() {
        if (customCatalog == null) {
            // Custom-catalog upload is metered. Auto-retry on a transport
            // failure could double-charge for the same audio fingerprinting,
            // so we cap customCatalog.add at exactly 1 attempt regardless of
            // the configured maxRetries. A transient failure surfaces as a
            // clean exception instead of a silent re-upload. Other mutating
            // operations (streams.add / setUrl / delete / setCallbackUrl)
            // keep the standard MUTATING policy — they're server-idempotent
            // on radioId and safe to retry on pre-upload connection errors.
            RetryPolicy noRetry = new RetryPolicy(RetryClass.MUTATING, 1, 0);
            customCatalog = new AsyncCustomCatalog(http, noRetry, onDeprecation, apiBase);
        }
        return customCatalog;
    }

    public AsyncAdvanced advanced() {
        if (advanced == null) advanced = new AsyncAdvanced(http, recognitionPolicy, onDeprecation, apiBase);
        return advanced;
    }

    @Override
    public void close() {
        http.close();
        enterpriseHttp.close();
    }

    private static CompletableFuture<io.audd.internal.HttpResponse> doAsyncCall(HttpClient http, String url,
                                                                                Map<String, String> base,
                                                                                SourcePreparer.Prepared prepared) {
        Map<String, String> data = new HashMap<>(base);
        RequestBody body;
        try {
            if (prepared.isUrl()) {
                data.put("url", prepared.urlField());
                body = null;
            } else {
                body = prepared.reopener().open();
            }
        } catch (IOException e) {
            CompletableFuture<io.audd.internal.HttpResponse> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
        return http.postFormAsync(url, data, body);
    }
}
