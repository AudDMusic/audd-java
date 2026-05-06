package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.models.StreamCallbackPayload;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure helpers used by streams.* and by user webhook handlers. No HTTP, no SDK state. */
public final class CallbackHelpers {

    private static final int LONGPOLL_CATEGORY_LEN = 9;

    private CallbackHelpers() {}

    /**
     * Compute the 9-char longpoll category locally from token + radio_id.
     * Formula (per docs.audd.io/streams.md): hex-MD5 of (hex-MD5 of api_token,
     * concatenated with the radio_id rendered as a decimal string), truncated
     * to the first 9 hex chars.
     */
    public static String deriveLongpollCategory(String apiToken, int radioId) {
        String inner = md5Hex(apiToken.getBytes(StandardCharsets.UTF_8));
        String full = md5Hex((inner + radioId).getBytes(StandardCharsets.UTF_8));
        return full.substring(0, LONGPOLL_CATEGORY_LEN);
    }

    /** Parse a callback POST body into a typed payload. */
    public static StreamCallbackPayload parseCallback(JsonNode body) {
        return StreamCallbackPayload.parse(body);
    }

    /**
     * Append {@code ?return=<metadata>} (or merge as {@code &return=}) to the callback URL.
     *
     * <p>If {@code returnMetadata} is {@code null}, returns the URL unchanged.
     * If the URL already has a {@code return} query parameter, throws to
     * avoid silent overwrite.</p>
     */
    public static String addReturnToUrl(String url, List<String> returnMetadata) {
        if (returnMetadata == null) return url;
        return addReturnToUrl(url, String.join(",", returnMetadata));
    }

    public static String addReturnToUrl(String url, String returnMetadata) {
        if (returnMetadata == null) return url;
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid URL: " + url, e);
        }
        Map<String, List<String>> qs = parseQuery(parsed.getRawQuery());
        if (qs.containsKey("return")) {
            throw new AudDInvalidRequestError(0,
                "URL already contains a `return` query parameter; pass returnMetadata=null "
                + "or remove the parameter from the URL — refusing to silently overwrite.",
                0, null, Collections.emptyMap(), null, null, null);
        }
        qs.put("return", List.of(returnMetadata));
        String newQ = encodeQuery(qs);
        // Reassemble the URL by hand — URI(...) string ctors would double-encode
        // the query we already encoded above.
        StringBuilder sb = new StringBuilder();
        if (parsed.getScheme() != null) sb.append(parsed.getScheme()).append("://");
        if (parsed.getRawAuthority() != null) sb.append(parsed.getRawAuthority());
        if (parsed.getRawPath() != null) sb.append(parsed.getRawPath());
        if (!newQ.isEmpty()) sb.append('?').append(newQ);
        if (parsed.getRawFragment() != null) sb.append('#').append(parsed.getRawFragment());
        return sb.toString();
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.computeIfAbsent(decode(k), _k -> new ArrayList<>()).add(decode(v));
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String encodeQuery(Map<String, List<String>> qs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> e : qs.entrySet()) {
            for (String v : e.getValue()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(bytes);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
