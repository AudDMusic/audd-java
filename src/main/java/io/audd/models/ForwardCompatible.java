package io.audd.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for typed models that round-trip unknown fields. Forward
 * compatibility (design spec §5.2): every typed model exposes both typed
 * fields we know about today AND an {@code extras} map for keys we don't.
 *
 * <p>Also stores the full unparsed JSON tree on {@link #rawResponse()} for
 * users who want zero parsing dependency on us.</p>
 */
public abstract class ForwardCompatible {
    private final Map<String, Object> extras = new LinkedHashMap<>();
    private JsonNode rawResponse;

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> extras() {
        return Collections.unmodifiableMap(extras);
    }

    @JsonIgnore
    public JsonNode rawResponse() {
        return rawResponse;
    }

    /** Internal: invoked by callers that decode the model from a JsonNode. */
    public void setRawResponse(JsonNode raw) {
        this.rawResponse = raw;
    }
}
