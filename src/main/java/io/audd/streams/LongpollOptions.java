package io.audd.streams;

/** Per-call options for {@link Streams#longpoll(String, LongpollOptions)}. */
public final class LongpollOptions {
    private final Long sinceTime;
    private final int timeout;
    private final boolean skipCallbackCheck;

    private LongpollOptions(Builder b) {
        this.sinceTime = b.sinceTime;
        this.timeout = b.timeout;
        this.skipCallbackCheck = b.skipCallbackCheck;
    }

    public Long sinceTime() { return sinceTime; }
    public int timeout() { return timeout; }
    public boolean skipCallbackCheck() { return skipCallbackCheck; }

    public static LongpollOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long sinceTime;
        private int timeout = 50;
        private boolean skipCallbackCheck = false;

        public Builder sinceTime(Long t) { this.sinceTime = t; return this; }
        public Builder timeout(int seconds) { this.timeout = seconds; return this; }
        public Builder skipCallbackCheck(boolean v) { this.skipCallbackCheck = v; return this; }
        public LongpollOptions build() { return new LongpollOptions(this); }
    }
}
