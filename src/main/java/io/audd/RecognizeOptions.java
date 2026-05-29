package io.audd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-call options for {@link AudD#recognize(Object, RecognizeOptions)} and
 * {@link AsyncAudD#recognize(Object, RecognizeOptions)}. All fields are
 * optional; pass {@link #defaults()} or null for server-side defaults.
 */
public final class RecognizeOptions {
    private final List<String> returnMetadata;
    private final String market;
    private final Long timeoutMs;
    private final Map<String, String> extraParameters;

    private RecognizeOptions(Builder b) {
        this.returnMetadata = b.returnMetadata == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(b.returnMetadata));
        this.market = b.market;
        this.timeoutMs = b.timeoutMs;
        this.extraParameters = b.extraParameters == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.extraParameters));
    }

    public List<String> returnMetadata() { return returnMetadata; }
    public String market() { return market; }
    public Long timeoutMs() { return timeoutMs; }
    /** Additional form fields the typed params don't cover. Typed params win on collision. */
    public Map<String, String> extraParameters() { return extraParameters; }

    public static RecognizeOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<String> returnMetadata;
        private String market;
        private Long timeoutMs;
        private Map<String, String> extraParameters;

        public Builder returnMetadata(List<String> values) {
            this.returnMetadata = values;
            return this;
        }
        public Builder returnMetadata(String... values) {
            this.returnMetadata = values == null ? null : List.of(values);
            return this;
        }
        public Builder market(String market) {
            this.market = market;
            return this;
        }
        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        public Builder extraParameters(Map<String, String> extras) {
            this.extraParameters = extras;
            return this;
        }
        public RecognizeOptions build() { return new RecognizeOptions(this); }
    }
}
