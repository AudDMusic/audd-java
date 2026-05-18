package io.audd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-call options for {@link AudD#recognizeEnterprise(Object, EnterpriseOptions)} and
 * its async equivalent. All fields optional.
 */
public final class EnterpriseOptions {
    private final List<String> returnMetadata;
    private final Integer skip;
    private final Integer every;
    private final Integer limit;
    private final Integer skipFirstSeconds;
    private final Boolean useTimecode;
    private final Boolean accurateOffsets;
    private final Long timeoutMs;
    private final Map<String, String> extraParameters;

    private EnterpriseOptions(Builder b) {
        this.returnMetadata = b.returnMetadata == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(b.returnMetadata));
        this.skip = b.skip;
        this.every = b.every;
        this.limit = b.limit;
        this.skipFirstSeconds = b.skipFirstSeconds;
        this.useTimecode = b.useTimecode;
        this.accurateOffsets = b.accurateOffsets;
        this.timeoutMs = b.timeoutMs;
        this.extraParameters = b.extraParameters == null
            ? null
            : Collections.unmodifiableMap(new LinkedHashMap<>(b.extraParameters));
    }

    public List<String> returnMetadata() { return returnMetadata; }
    public Integer skip() { return skip; }
    public Integer every() { return every; }
    public Integer limit() { return limit; }
    public Integer skipFirstSeconds() { return skipFirstSeconds; }
    public Boolean useTimecode() { return useTimecode; }
    public Boolean accurateOffsets() { return accurateOffsets; }
    public Long timeoutMs() { return timeoutMs; }
    /** Additional form fields the typed params don't cover. Typed params win on collision. */
    public Map<String, String> extraParameters() { return extraParameters; }

    public static EnterpriseOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<String> returnMetadata;
        private Integer skip;
        private Integer every;
        private Integer limit;
        private Integer skipFirstSeconds;
        private Boolean useTimecode;
        private Boolean accurateOffsets;
        private Long timeoutMs;
        private Map<String, String> extraParameters;

        public Builder returnMetadata(List<String> values) { this.returnMetadata = values; return this; }
        public Builder returnMetadata(String... values) {
            this.returnMetadata = values == null ? null : List.of(values);
            return this;
        }
        public Builder skip(Integer skip) { this.skip = skip; return this; }
        public Builder every(Integer every) { this.every = every; return this; }
        public Builder limit(Integer limit) { this.limit = limit; return this; }
        public Builder skipFirstSeconds(Integer s) { this.skipFirstSeconds = s; return this; }
        public Builder useTimecode(Boolean b) { this.useTimecode = b; return this; }
        public Builder accurateOffsets(Boolean b) { this.accurateOffsets = b; return this; }
        public Builder timeoutMs(Long t) { this.timeoutMs = t; return this; }
        public Builder extraParameters(Map<String, String> extras) { this.extraParameters = extras; return this; }
        public EnterpriseOptions build() { return new EnterpriseOptions(this); }
    }
}
