package io.audd.customcatalog;

import io.audd.errors.AudDConnectionError;
import io.audd.internal.HttpClient;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryPolicy;
import io.audd.internal.SourcePreparer;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Async client for the custom-catalog endpoint. <strong>Not</strong> for recognition. */
public final class AsyncCustomCatalog {
    private final HttpClient http;
    private final RetryPolicy mutatingPolicy;
    private final Consumer<String> onDeprecation;
    private final String uploadUrl;

    public AsyncCustomCatalog(HttpClient http, RetryPolicy mutatingPolicy, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.mutatingPolicy = mutatingPolicy;
        this.onDeprecation = onDeprecation;
        this.uploadUrl = apiBase + "/upload/";
    }

    /** See {@link CustomCatalog#add(int, Object)}. NOT for music recognition. */
    public CompletableFuture<Void> add(int audioId, Object source) {
        SourcePreparer.Prepared prepared;
        try {
            prepared = SourcePreparer.prepare(source);
        } catch (IOException e) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new AudDConnectionError("could not prepare source: " + e.getMessage(), e));
            return f;
        }
        Map<String, String> base = new HashMap<>();
        base.put("audio_id", String.valueOf(audioId));

        return mutatingPolicy.runAsync(() -> {
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
            return http.postFormAsync(uploadUrl, data, body);
        }).thenApply(resp -> {
            ResponseDecoder.decodeOrRaise(resp, onDeprecation, true);
            return null;
        });
    }
}
