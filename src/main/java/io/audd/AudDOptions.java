package io.audd;

import okhttp3.OkHttpClient;

import java.util.function.Consumer;

/**
 * Configuration knobs shared by {@link AudD} and {@link AsyncAudD} builders.
 * Every field is optional; sensible defaults apply.
 */
public final class AudDOptions {
    private final String apiToken;
    private final int maxRetries;
    private final long backoffFactorMs;
    private final long standardTimeoutSeconds;
    private final long enterpriseTimeoutSeconds;
    private final OkHttpClient httpClient;
    private final Consumer<String> onDeprecation;
    private final Consumer<AudDEvent> onEvent;
    private final String apiBase;
    private final String enterpriseBase;

    AudDOptions(String apiToken, int maxRetries, long backoffFactorMs,
                long standardTimeoutSeconds, long enterpriseTimeoutSeconds,
                OkHttpClient httpClient, Consumer<String> onDeprecation,
                Consumer<AudDEvent> onEvent,
                String apiBase, String enterpriseBase) {
        this.apiToken = apiToken;
        this.maxRetries = maxRetries;
        this.backoffFactorMs = backoffFactorMs;
        this.standardTimeoutSeconds = standardTimeoutSeconds;
        this.enterpriseTimeoutSeconds = enterpriseTimeoutSeconds;
        this.httpClient = httpClient;
        this.onDeprecation = onDeprecation;
        this.onEvent = onEvent;
        this.apiBase = apiBase;
        this.enterpriseBase = enterpriseBase;
    }

    public String apiToken() { return apiToken; }
    public int maxRetries() { return maxRetries; }
    public long backoffFactorMs() { return backoffFactorMs; }
    public long standardTimeoutSeconds() { return standardTimeoutSeconds; }
    public long enterpriseTimeoutSeconds() { return enterpriseTimeoutSeconds; }
    public OkHttpClient httpClient() { return httpClient; }
    public Consumer<String> onDeprecation() { return onDeprecation; }
    public Consumer<AudDEvent> onEvent() { return onEvent; }
    public String apiBase() { return apiBase; }
    public String enterpriseBase() { return enterpriseBase; }
}
