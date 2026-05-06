package io.audd;

import com.fasterxml.jackson.databind.JsonNode;
import io.audd.advanced.Advanced;
import io.audd.customcatalog.CustomCatalog;
import io.audd.errors.AudDConnectionError;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryClass;
import io.audd.internal.RetryPolicy;
import io.audd.internal.SourcePreparer;
import io.audd.models.EnterpriseChunkResult;
import io.audd.models.EnterpriseMatch;
import io.audd.models.RecognitionResult;
import io.audd.streams.Streams;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sync client for the AudD music recognition API. Use {@link #builder()} or
 * {@code new AudD(apiToken)}; safe to use in try-with-resources.
 *
 * <p>For per-call options pass {@link RecognizeOptions} / {@link EnterpriseOptions};
 * for sub-clients call {@link #streams()}, {@link #customCatalog()}, {@link #advanced()}.
 */
public final class AudD implements AutoCloseable {
    public static final String API_BASE_DEFAULT = "https://api.audd.io";
    public static final String ENTERPRISE_BASE_DEFAULT = "https://enterprise.audd.io";

    /**
     * Environment variable consulted by {@link #fromEnvironment()} and the
     * {@link Builder} when {@code apiToken} is null/empty. See design spec §7.11.
     */
    public static final String API_TOKEN_ENV_VAR = "AUDD_API_TOKEN";

    private static final String MISSING_TOKEN_MESSAGE =
        "AudD apiToken not supplied and " + API_TOKEN_ENV_VAR + " env var is unset. "
        + "Get a token at https://dashboard.audd.io and pass it as AudD.builder().apiToken(...) "
        + "or set " + API_TOKEN_ENV_VAR + ".";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger("io.audd");

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

    private Streams streams;
    private CustomCatalog customCatalog;
    private Advanced advanced;

    public AudD(String apiToken) {
        this(builder().apiToken(apiToken).buildOptions());
    }

    /**
     * Build an {@link AudD} using the {@value #API_TOKEN_ENV_VAR} environment
     * variable. Throws {@link IllegalArgumentException} with a dashboard-link
     * hint when the variable is unset or empty. See design spec §7.11.
     */
    public static AudD fromEnvironment() {
        return builder().build();
    }

    /**
     * Resolve a token: explicit arg → {@value #API_TOKEN_ENV_VAR} env var →
     * {@link IllegalArgumentException}. The error message points at
     * https://dashboard.audd.io. See design spec §7.11.
     */
    static String resolveToken(String explicit) {
        if (explicit != null && !explicit.isEmpty()) return explicit;
        String env = System.getenv(API_TOKEN_ENV_VAR);
        if (env != null && !env.isEmpty()) return env;
        throw new IllegalArgumentException(MISSING_TOKEN_MESSAGE);
    }

    AudD(AudDOptions opts) {
        this.apiToken = new AtomicReference<>(opts.apiToken());
        this.http = new HttpClient(opts.apiToken(), opts.httpClient(), opts.standardTimeoutSeconds());
        this.enterpriseHttp = new HttpClient(opts.apiToken(), opts.httpClient(), opts.enterpriseTimeoutSeconds());
        this.readPolicy = new RetryPolicy(RetryClass.READ, opts.maxRetries(), opts.backoffFactorMs());
        this.recognitionPolicy = new RetryPolicy(RetryClass.RECOGNITION, opts.maxRetries(), opts.backoffFactorMs());
        this.mutatingPolicy = new RetryPolicy(RetryClass.MUTATING, opts.maxRetries(), opts.backoffFactorMs());
        this.onDeprecation = opts.onDeprecation();
        this.onEvent = opts.onEvent();
        this.apiBase = opts.apiBase() != null ? opts.apiBase() : API_BASE_DEFAULT;
        this.enterpriseBase = opts.enterpriseBase() != null ? opts.enterpriseBase() : ENTERPRISE_BASE_DEFAULT;
    }

    /**
     * Invoke the {@link #onEvent} hook (if any) swallowing any exception so
     * observability never breaks the request path. Logs swallowed throwables
     * at FINE level under logger {@code io.audd}.
     */
    static void safeEmit(Consumer<AudDEvent> hook, AudDEvent event) {
        if (hook == null) return;
        try {
            hook.accept(event);
        } catch (Throwable t) {
            LOGGER.log(java.util.logging.Level.FINE, "onEvent hook raised; suppressed", t);
        }
    }

    public String apiBase() { return apiBase; }
    public String enterpriseBase() { return enterpriseBase; }

    /** Recognize from a URL, file path, Path, File, byte[], or InputStream. */
    public RecognitionResult recognize(Object source) {
        return recognize(source, RecognizeOptions.defaults());
    }

    public RecognitionResult recognize(Object source, RecognizeOptions opts) {
        SourcePreparer.Prepared prepared = prepareSource(source);
        Map<String, String> baseFields = new HashMap<>();
        if (opts != null && opts.returnMetadata() != null) {
            baseFields.put("return", String.join(",", opts.returnMetadata()));
        }
        if (opts != null && opts.market() != null) {
            baseFields.put("market", opts.market());
        }

        String url = apiBase + "/";
        safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.REQUEST).method("recognize").url(url).build());
        long started = System.nanoTime();

        HttpResponse resp;
        try {
            resp = recognitionPolicy.runSync(() -> doRecognizeCall(http, url, baseFields, prepared));
        } catch (IOException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> extras = new HashMap<>();
            extras.put("error_type", e.getClass().getSimpleName());
            safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.EXCEPTION).method("recognize").url(url)
                    .elapsedMs(elapsed).extras(extras).build());
            throw new AudDConnectionError(e.getMessage() == null ? "connection error" : e.getMessage(), e);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.RESPONSE).method("recognize").url(url)
                .requestId(resp.requestId()).httpStatus(resp.httpStatus()).elapsedMs(elapsed).build());

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
    }

    /** Enterprise (large-file) recognition. Returns a flat list of matches across all chunks. */
    public List<EnterpriseMatch> recognizeEnterprise(Object source) {
        return recognizeEnterprise(source, EnterpriseOptions.defaults());
    }

    public List<EnterpriseMatch> recognizeEnterprise(Object source, EnterpriseOptions opts) {
        SourcePreparer.Prepared prepared = prepareSource(source);
        Map<String, String> baseFields = buildEnterpriseFields(opts);

        String url = enterpriseBase + "/";
        safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.REQUEST).method("recognize").url(url).build());
        long started = System.nanoTime();

        HttpResponse resp;
        try {
            resp = recognitionPolicy.runSync(() -> doRecognizeCall(enterpriseHttp, url, baseFields, prepared));
        } catch (IOException e) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> extras = new HashMap<>();
            extras.put("error_type", e.getClass().getSimpleName());
            safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.EXCEPTION).method("recognize").url(url)
                    .elapsedMs(elapsed).extras(extras).build());
            throw new AudDConnectionError(e.getMessage() == null ? "connection error" : e.getMessage(), e);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        safeEmit(onEvent, AudDEvent.builder().kind(AudDEvent.Kind.RESPONSE).method("recognize").url(url)
                .requestId(resp.requestId()).httpStatus(resp.httpStatus()).elapsedMs(elapsed).build());
        JsonNode body = ResponseDecoder.decodeOrRaise(resp, onDeprecation, false);
        JsonNode result = body.path("result");
        List<EnterpriseMatch> out = new ArrayList<>();
        if (result == null || !result.isArray()) return out;
        for (JsonNode chunkNode : result) {
            try {
                EnterpriseChunkResult chunk = MAPPER.treeToValue(chunkNode, EnterpriseChunkResult.class);
                if (chunk != null && chunk.songs() != null) {
                    out.addAll(chunk.songs());
                }
            } catch (Exception e) {
                throw new io.audd.errors.AudDSerializationError("Failed to decode EnterpriseChunkResult", resp.rawText());
            }
        }
        return out;
    }

    public Streams streams() {
        if (streams == null) {
            streams = new Streams(http, readPolicy, mutatingPolicy, apiToken::get, onDeprecation, apiBase);
        }
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

    public CustomCatalog customCatalog() {
        if (customCatalog == null) {
            customCatalog = new CustomCatalog(http, mutatingPolicy, onDeprecation, apiBase);
        }
        return customCatalog;
    }

    public Advanced advanced() {
        if (advanced == null) {
            // C2: Advanced uses RECOGNITION policy (find_lyrics is metered).
            advanced = new Advanced(http, recognitionPolicy, onDeprecation, apiBase);
        }
        return advanced;
    }

    @Override
    public void close() {
        http.close();
        enterpriseHttp.close();
    }

    static HttpResponse doRecognizeCall(HttpClient http, String url, Map<String, String> baseFields,
                                        SourcePreparer.Prepared prepared) throws IOException {
        Map<String, String> fields = new HashMap<>(baseFields);
        RequestBody body = null;
        if (prepared.isUrl()) {
            fields.put("url", prepared.urlField());
        } else {
            body = prepared.reopener().open();
        }
        return http.postForm(url, fields, body);
    }

    static SourcePreparer.Prepared prepareSource(Object source) {
        try {
            return SourcePreparer.prepare(source);
        } catch (IOException e) {
            throw new AudDConnectionError("could not prepare source: " + e.getMessage(), e);
        }
    }

    static Map<String, String> buildEnterpriseFields(EnterpriseOptions opts) {
        Map<String, String> fields = new HashMap<>();
        if (opts == null) return fields;
        if (opts.returnMetadata() != null) fields.put("return", String.join(",", opts.returnMetadata()));
        if (opts.skip() != null) fields.put("skip", opts.skip().toString());
        if (opts.every() != null) fields.put("every", opts.every().toString());
        if (opts.limit() != null) fields.put("limit", opts.limit().toString());
        if (opts.skipFirstSeconds() != null) fields.put("skip_first_seconds", opts.skipFirstSeconds().toString());
        if (opts.useTimecode() != null) fields.put("use_timecode", opts.useTimecode() ? "true" : "false");
        if (opts.accurateOffsets() != null) fields.put("accurate_offsets", opts.accurateOffsets() ? "true" : "false");
        return fields;
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder shared with {@link AsyncAudD}. */
    public static final class Builder {
        private String apiToken;
        private int maxRetries = 3;
        private long backoffFactorMs = 500;
        private long standardTimeoutSeconds = HttpClient.DEFAULT_READ_TIMEOUT_S;
        private long enterpriseTimeoutSeconds = HttpClient.ENTERPRISE_READ_TIMEOUT_S;
        private OkHttpClient httpClient;
        private Consumer<String> onDeprecation;
        private Consumer<AudDEvent> onEvent;
        private String apiBase;
        private String enterpriseBase;

        public Builder apiToken(String token) { this.apiToken = token; return this; }
        public Builder maxRetries(int n) { this.maxRetries = n; return this; }
        public Builder backoffFactorMs(long ms) { this.backoffFactorMs = ms; return this; }
        public Builder standardTimeoutSeconds(long s) { this.standardTimeoutSeconds = s; return this; }
        public Builder enterpriseTimeoutSeconds(long s) { this.enterpriseTimeoutSeconds = s; return this; }
        public Builder httpClient(OkHttpClient client) { this.httpClient = client; return this; }
        public Builder onDeprecation(Consumer<String> callback) { this.onDeprecation = callback; return this; }
        /**
         * Register an inspection hook that receives lifecycle events
         * ({@link AudDEvent.Kind#REQUEST}, {@link AudDEvent.Kind#RESPONSE},
         * {@link AudDEvent.Kind#EXCEPTION}) for every API call. Off by default.
         * Hook exceptions are swallowed at FINE log level so observability
         * never breaks the request path. Spec §7.7a.
         */
        public Builder onEvent(Consumer<AudDEvent> hook) { this.onEvent = hook; return this; }
        /** Override the default API base URL ({@value AudD#API_BASE_DEFAULT}). For tests. */
        public Builder apiBase(String base) { this.apiBase = base; return this; }
        /** Override the default enterprise base URL ({@value AudD#ENTERPRISE_BASE_DEFAULT}). For tests. */
        public Builder enterpriseBase(String base) { this.enterpriseBase = base; return this; }

        public AudDOptions buildOptions() {
            String token = resolveToken(apiToken);
            return new AudDOptions(token, maxRetries, backoffFactorMs,
                    standardTimeoutSeconds, enterpriseTimeoutSeconds, httpClient, onDeprecation,
                    onEvent, apiBase, enterpriseBase);
        }

        public AudD build() { return new AudD(buildOptions()); }
        public AsyncAudD buildAsync() { return new AsyncAudD(buildOptions()); }
    }
}
