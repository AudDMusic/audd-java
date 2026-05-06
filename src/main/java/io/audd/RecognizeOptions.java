package io.audd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-call options for {@link AudD#recognize(Object, RecognizeOptions)} and
 * {@link AsyncAudD#recognize(Object, RecognizeOptions)}. All fields are
 * optional; pass {@link #defaults()} or null for server-side defaults.
 */
public final class RecognizeOptions {
    private final List<String> returnMetadata;
    private final String market;
    private final Long timeoutMs;

    private RecognizeOptions(Builder b) {
        this.returnMetadata = b.returnMetadata == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(b.returnMetadata));
        this.market = b.market;
        this.timeoutMs = b.timeoutMs;
    }

    public List<String> returnMetadata() { return returnMetadata; }
    public String market() { return market; }
    public Long timeoutMs() { return timeoutMs; }

    public static RecognizeOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private List<String> returnMetadata;
        private String market;
        private Long timeoutMs;

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
        public RecognizeOptions build() { return new RecognizeOptions(this); }
    }
}
