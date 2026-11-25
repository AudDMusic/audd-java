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

    /** Server timestamp (milliseconds) to resume from, or {@code null} to start from now. */
    public Long sinceTime() { return sinceTime; }
    /** Seconds each longpoll request waits for new events: 1 to 120. */
    public int timeout() { return timeout; }
    public boolean skipCallbackCheck() { return skipCallbackCheck; }

    public static LongpollOptions defaults() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long sinceTime;
        private int timeout = 50;
        private boolean skipCallbackCheck = false;

        /**
         * Deliver only events newer than this server timestamp, in
         * milliseconds, as carried by an earlier longpoll response. Default:
         * {@code null}, start from now.
         */
        public Builder sinceTime(Long t) { this.sinceTime = t; return this; }
        /**
         * How long, in seconds, the server holds each longpoll request open
         * while waiting for events: 1 to 120 (default 50). The server answers
         * a value outside that range with an error, which reaches
         * {@link LongpollPoll#onError}.
         */
        public Builder timeout(int seconds) { this.timeout = seconds; return this; }
        /** Skip the preflight that checks a callback URL is configured. */
        public Builder skipCallbackCheck(boolean v) { this.skipCallbackCheck = v; return this; }
        public LongpollOptions build() { return new LongpollOptions(this); }
    }
}
