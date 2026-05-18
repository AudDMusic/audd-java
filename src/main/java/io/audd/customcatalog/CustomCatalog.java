package io.audd.customcatalog;

import io.audd.errors.AudDConnectionError;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.ResponseDecoder;
import io.audd.internal.RetryPolicy;
import io.audd.internal.SourcePreparer;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sync client for the custom-catalog endpoint. <strong>Not</strong> for
 * recognition — see method docs.
 */
public final class CustomCatalog {
    private final HttpClient http;
    private final RetryPolicy mutatingPolicy;
    private final Consumer<String> onDeprecation;
    private final String uploadUrl;

    public CustomCatalog(HttpClient http, RetryPolicy mutatingPolicy, Consumer<String> onDeprecation, String apiBase) {
        this.http = http;
        this.mutatingPolicy = mutatingPolicy;
        this.onDeprecation = onDeprecation;
        this.uploadUrl = apiBase + "/upload/";
    }

    /**
     * <strong>This is NOT how you submit audio for music recognition.</strong>
     * For recognition, use {@code AudD.recognize(...)} (or
     * {@code AudD.recognizeEnterprise(...)} for files longer than 25 seconds).
     * This method adds a song to your <strong>private fingerprint catalog</strong>
     * so AudD's recognition can later identify <em>your own</em> tracks for
     * <em>your account only</em>. Requires special access — contact
     * api@audd.io if you need it enabled.
     *
     * <p>Calling again with the same {@code audioId} re-fingerprints that
     * slot. There is no public list/delete endpoint; track {@code audioId}
     * &harr; song mappings on your side.</p>
     *
     * @param audioId integer slot in your custom catalog
     * @param source URL string, file path, Path, File, byte[], or InputStream
     */
    public void add(int audioId, Object source) {
        SourcePreparer.Prepared prepared;
        try {
            prepared = SourcePreparer.prepare(source);
        } catch (IOException e) {
            throw new AudDConnectionError("could not prepare source: " + e.getMessage(), e);
        }
        Map<String, String> base = new HashMap<>();
        base.put("audio_id", String.valueOf(audioId));

        HttpResponse resp;
        try {
            resp = mutatingPolicy.runSync(() -> {
                Map<String, String> data = new HashMap<>(base);
                RequestBody body = null;
                if (prepared.isUrl()) {
                    data.put("url", prepared.urlField());
                } else {
                    body = prepared.reopener().open();
                }
                return http.postForm(uploadUrl, data, body);
            });
        } catch (IOException e) {
            throw new AudDConnectionError(e.getMessage() == null ? "connection error" : e.getMessage(), e);
        }
        // customCatalogContext=true upgrades 904/905 to AudDCustomCatalogAccessError.
        ResponseDecoder.decodeOrRaise(resp, onDeprecation, true);
    }
}
