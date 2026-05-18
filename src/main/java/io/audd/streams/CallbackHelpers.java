package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDSerializationError;
import io.audd.models.CallbackEvent;
import io.audd.models.StreamCallbackMatch;
import io.audd.models.StreamCallbackNotification;
import io.audd.models.StreamCallbackSong;

import java.io.IOException;
import java.io.InputStream;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /**
     * Parse a callback POST body (already-parsed JSON tree) into a typed event.
     * Recognition callbacks have an outer {@code result} block; notification
     * callbacks have a {@code notification} block; the discrimination is by
     * key. Exactly one of {@link CallbackEvent#match()} /
     * {@link CallbackEvent#notification()} is present on success.
     *
     * <p>Throws {@link AudDSerializationError} when the body is missing
     * both keys, when {@code result.results} is empty, or when the inner
     * JSON cannot be decoded into the typed shape.</p>
     */
    public static CallbackEvent parseCallback(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new AudDSerializationError("callback body must be a JSON object",
                body == null ? "" : body.toString());
        }

        if (body.has("notification")) {
            JsonNode notifNode = body.get("notification");
            StreamCallbackNotification n;
            try {
                n = MAPPER.treeToValue(notifNode, StreamCallbackNotification.class);
            } catch (Exception e) {
                throw new AudDSerializationError("callback notification: " + e.getMessage(), body.toString());
            }
            n.setRawResponse(body);
            if (body.has("time") && body.get("time").isNumber()) {
                n.setTime(body.get("time").asLong());
            }
            return CallbackEvent.ofNotification(n);
        }

        if (body.has("result")) {
            return CallbackEvent.ofMatch(parseMatch(body.get("result"), body));
        }

        // Some callbacks (and longpoll responses) come without an outer
        // `result` wrapper — the match fields sit at the top level. Detect
        // by presence of the `results` array.
        if (body.has("results") && body.get("results").isArray()) {
            return CallbackEvent.ofMatch(parseMatch(body, body));
        }

        throw new AudDSerializationError(
            "callback body has neither result nor notification", body.toString());
    }

    /** Parse a callback POST body (raw bytes) into a typed event. */
    public static CallbackEvent parseCallback(byte[] body) {
        if (body == null || body.length == 0) {
            throw new AudDSerializationError("callback body is empty", "");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new AudDSerializationError(
                "callback body is not valid JSON: " + e.getMessage(),
                new String(body, StandardCharsets.UTF_8));
        }
        return parseCallback(node);
    }

    /**
     * Parse a callback POST body from an {@link InputStream}. Reads the
     * stream to EOF; does NOT close it (the caller owns the stream). Use
     * this overload from your servlet/HTTP-handler code:
     *
     * <pre>{@code
     * try (InputStream in = httpRequest.getInputStream()) {
     *     CallbackEvent event = Streams.parseCallback(in);
     *     ...
     * }
     * }</pre>
     */
    public static CallbackEvent parseCallback(InputStream body) {
        if (body == null) {
            throw new AudDSerializationError("callback body is null", "");
        }
        byte[] bytes;
        try {
            bytes = body.readAllBytes();
        } catch (IOException e) {
            throw new AudDSerializationError("failed to read callback body: " + e.getMessage(), "");
        }
        return parseCallback(bytes);
    }

    /** Decode a {@code result} block into a typed match. */
    private static StreamCallbackMatch parseMatch(JsonNode resultNode, JsonNode fullBody) {
        if (resultNode == null || !resultNode.isObject()) {
            throw new AudDSerializationError(
                "callback result is not an object", fullBody.toString());
        }
        StreamCallbackMatch m;
        try {
            m = MAPPER.treeToValue(resultNode, StreamCallbackMatch.class);
        } catch (Exception e) {
            throw new AudDSerializationError("callback result: " + e.getMessage(), fullBody.toString());
        }
        JsonNode resultsArr = resultNode.get("results");
        if (resultsArr == null || !resultsArr.isArray() || resultsArr.size() == 0) {
            throw new AudDSerializationError("callback result.results is empty", fullBody.toString());
        }
        List<StreamCallbackSong> songs = new ArrayList<>(resultsArr.size());
        for (JsonNode el : resultsArr) {
            try {
                StreamCallbackSong s = MAPPER.treeToValue(el, StreamCallbackSong.class);
                if (s != null) s.setRawResponse(el);
                songs.add(s);
            } catch (Exception e) {
                throw new AudDSerializationError(
                    "callback result.results entry: " + e.getMessage(), fullBody.toString());
            }
        }
        m.setSong(songs.get(0));
        m.setAlternatives(songs.size() > 1 ? songs.subList(1, songs.size()) : Collections.emptyList());
        m.setRawResponse(fullBody);
        return m;
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
