package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDException;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDServerError;
import io.audd.errors.ErrorMapping;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.Json;
import io.audd.internal.RetryPolicy;
import io.audd.models.CallbackEvent;
import io.audd.models.StreamCallbackMatch;
import io.audd.models.StreamCallbackNotification;
import okhttp3.Call;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Active long-poll subscription. Three callback registrations — match,
 * notification, error — drive a blocking dispatch loop.
 *
 * <p>Idiomatic usage:</p>
 *
 * <pre>{@code
 * try (LongpollPoll poll = audd.streams().longpoll(category)) {
 *     poll.onMatch(m -> System.out.println(m.song().artist() + " — " + m.song().title()));
 *     poll.onNotification(n -> System.out.println("notif: " + n.notificationMessage()));
 *     poll.onError(err -> System.err.println(err));
 *     poll.run();   // blocks until close() or terminal error
 * }
 * }</pre>
 *
 * <p>Or async:</p>
 *
 * <pre>{@code
 * LongpollPoll poll = audd.streams().longpoll(category);
 * poll.onMatch(...).onNotification(...).onError(...);
 * CompletableFuture<Void> done = poll.runAsync();
 * // later: poll.close();
 * }</pre>
 *
 * <p>Each request waits up to {@link LongpollOptions#timeout()} seconds for
 * new events and carries the category only, never the api_token. Events
 * arrive in order: a recognition goes to the match callback, a stream
 * notification to the notification callback. A recognition with no songs,
 * or an event the SDK can't parse, is skipped. The next request asks only
 * for events newer than the newest one delivered.</p>
 *
 * <p>The error callback is single-shot: when it fires, the loop exits and
 * no more match/notification callbacks will be invoked. It fires for an
 * error answer from the server (an {@link AudDInvalidRequestError} carrying
 * the server's text, for example for a timeout outside 1-120 seconds), for
 * an HTTP error status, for network failures and HTTP 408/429/5xx that
 * persist through the client's {@code maxRetries} attempts (an
 * {@link AudDConnectionError} or {@link AudDServerError}), and for an
 * exception thrown by your match or notification callback. A response with
 * nothing new in it (an unknown shape, an empty or non-JSON body) never ends
 * the loop: the next request waits 1 second, doubling up to 30 seconds while
 * such responses continue.</p>
 *
 * <p>{@link #close()} is idempotent and safe to call from any thread: it
 * cancels the request in flight, interrupts a wait, and ends the loop
 * without surfacing an error.</p>
 */
public final class LongpollPoll implements AutoCloseable {
    /** First pause after a response with nothing new in it. */
    static final long IDLE_BACKOFF_INITIAL_MS = 1_000;
    /** Longest pause between requests while responses bring nothing new. */
    static final long IDLE_BACKOFF_MAX_MS = 30_000;
    /** Read-timeout margin on top of the poll timeout, so the server's answer arrives first. */
    static final long READ_TIMEOUT_MARGIN_S = 15;

    private static final ObjectMapper MAPPER = Json.mapper();

    private final HttpClient http;
    private final RetryPolicy readPolicy;
    private final String apiBase;
    private final String category;
    private final int timeout;
    private Long currentSince;
    private long idleBackoffInitialMs = IDLE_BACKOFF_INITIAL_MS;
    private long idleBackoffMaxMs = IDLE_BACKOFF_MAX_MS;

    private volatile Consumer<StreamCallbackMatch> onMatch = m -> {};
    private volatile Consumer<StreamCallbackNotification> onNotification = n -> {};
    private volatile Consumer<Throwable> onError = e -> {};

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch closedSignal = new CountDownLatch(1);
    private final AtomicReference<Call> inFlight = new AtomicReference<>();

    LongpollPoll(HttpClient http, RetryPolicy readPolicy, String apiBase,
                 String category, LongpollOptions opts) {
        this.http = http;
        this.readPolicy = readPolicy;
        this.apiBase = apiBase;
        this.category = category;
        this.timeout = opts.timeout();
        this.currentSince = opts.sinceTime();
    }

    /** Test hook: shorten the pauses between requests that bring nothing new. */
    LongpollPoll idleBackoff(long initialMs, long maxMs) {
        this.idleBackoffInitialMs = initialMs;
        this.idleBackoffMaxMs = maxMs;
        return this;
    }

    /**
     * Register the per-match callback. Replaces any previous registration.
     * The match always carries a song: {@link StreamCallbackMatch#song()} is
     * non-null here.
     */
    public LongpollPoll onMatch(Consumer<StreamCallbackMatch> cb) {
        this.onMatch = cb == null ? m -> {} : cb;
        return this;
    }

    /** Register the per-notification callback. Replaces any previous registration. */
    public LongpollPoll onNotification(Consumer<StreamCallbackNotification> cb) {
        this.onNotification = cb == null ? n -> {} : cb;
        return this;
    }

    /**
     * Register the terminal-error callback. Replaces any previous
     * registration. The callback fires at most once per poll lifetime; the
     * loop exits afterwards.
     */
    public LongpollPoll onError(Consumer<Throwable> cb) {
        this.onError = cb == null ? e -> {} : cb;
        return this;
    }

    /**
     * Block in the calling thread until {@link #close()} is invoked or a
     * terminal error fires. Throws {@link IllegalStateException} if the
     * poll is already running on another thread. Interrupting the thread
     * closes the poll.
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("LongpollPoll is already running");
        }
        try {
            loop();
        } finally {
            running.set(false);
        }
    }

    /**
     * Run the poll on a daemon thread; returns a {@link CompletableFuture}
     * that completes when the loop exits (normally on close or after the
     * error callback, or exceptionally if a callback rethrows).
     */
    public CompletableFuture<Void> runAsync() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                run();
                done.complete(null);
            } catch (Throwable th) {
                done.completeExceptionally(th);
            }
        }, "audd-longpoll-" + category);
        t.setDaemon(true);
        t.start();
        return done;
    }

    /**
     * Stop the poll: cancels the request in flight and any wait between
     * requests. Idempotent. Safe to call from any thread.
     */
    @Override
    public void close() {
        if (stopped.compareAndSet(false, true)) {
            closedSignal.countDown();
        }
        Call call = inFlight.get();
        if (call != null) call.cancel();
    }

    /** True after {@link #close()} has been invoked. */
    public boolean isClosed() { return stopped.get(); }

    private void loop() {
        int idleResponses = 0;
        while (!stopped.get()) {
            HttpResponse resp;
            try {
                resp = fetchWithRetries();
            } catch (IOException e) {
                if (!endedByCaller()) {
                    onError.accept(new AudDConnectionError(
                        e.getMessage() == null ? "connection error" : e.getMessage(), e));
                }
                return;
            } catch (RuntimeException e) {
                if (!endedByCaller()) onError.accept(e);
                return;
            }
            if (resp == null || stopped.get()) return;

            Page page = interpret(resp);
            if (page.error != null) {
                onError.accept(page.error);
                return;
            }
            if (resp.httpStatus() >= 400) {
                onError.accept(new AudDServerError(
                    0, "Longpoll endpoint returned HTTP " + resp.httpStatus(),
                    resp.httpStatus(), resp.requestId(),
                    Collections.emptyMap(), null, null, resp.jsonBody()));
                return;
            }

            for (CallbackEvent event : page.events) {
                if (stopped.get()) return;
                if (!deliver(event)) return;
            }
            if (stopped.get()) return;

            // An events response moves on only through its timestamps: with
            // the same since_time the next request would return the same events.
            boolean progressed = !page.eventsShape && !page.events.isEmpty();
            if (page.since != null && !page.since.equals(currentSince)) {
                currentSince = page.since;
                progressed = true;
            }
            if (progressed) {
                idleResponses = 0;
                continue;
            }
            if (!pause(idleDelayMs(idleResponses))) return;
            idleResponses++;
        }
    }

    /** Hand one event to its callback. Returns false when the loop must end. */
    private boolean deliver(CallbackEvent event) {
        try {
            if (event.isMatch()) {
                onMatch.accept(event.match().get());
            } else if (event.isNotification()) {
                onNotification.accept(event.notification().get());
            }
            return true;
        } catch (RuntimeException userExc) {
            onError.accept(userExc);
            return false;
        }
    }

    /**
     * One longpoll request, retried per the client's read policy (HTTP
     * 408/429/5xx and network failures, up to {@code maxRetries} attempts).
     * Returns {@code null} when the poll was closed.
     */
    private HttpResponse fetchWithRetries() throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("category", category);
        params.put("timeout", String.valueOf(timeout));
        if (currentSince != null) params.put("since_time", String.valueOf(currentSince));

        for (int attempt = 0; ; attempt++) {
            boolean lastAttempt = attempt + 1 >= readPolicy.maxAttempts();
            try {
                HttpResponse resp = fetchOnce(params);
                if (lastAttempt || !readPolicy.shouldRetryResponse(resp.httpStatus())) return resp;
            } catch (IOException e) {
                if (endedByCaller()) return null;
                if (lastAttempt || !readPolicy.shouldRetryException(e)) throw e;
            }
            if (!pause(readPolicy.backoffDelayMs(attempt))) return null;
        }
    }

    private HttpResponse fetchOnce(Map<String, String> params) throws IOException {
        Call call = http.newGetWithoutToken(apiBase + "/longpoll/", params,
            Math.max(1, timeout) + READ_TIMEOUT_MARGIN_S);
        inFlight.set(call);
        try {
            // close() may have run before the call was published.
            if (stopped.get()) call.cancel();
            return http.execute(call);
        } finally {
            inFlight.compareAndSet(call, null);
        }
    }

    /** True when the loop is ending because the caller closed it or interrupted its thread. */
    private boolean endedByCaller() {
        if (Thread.currentThread().isInterrupted()) close();
        return stopped.get();
    }

    /** Wait {@code ms}, or less if the poll is closed. Returns false when closed. */
    private boolean pause(long ms) {
        if (stopped.get()) return false;
        if (ms <= 0) return true;
        try {
            return !closedSignal.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            return false;
        }
    }

    /** Pause after the n-th consecutive response with nothing new in it (n counts from 0). */
    long idleDelayMs(int n) {
        long d = idleBackoffInitialMs;
        for (int i = 0; i < n && d < idleBackoffMaxMs; i++) d *= 2;
        return Math.min(d, idleBackoffMaxMs);
    }

    /** What one longpoll response yields. */
    static final class Page {
        final List<CallbackEvent> events = new ArrayList<>();
        /** The next since_time, when the response carried one. */
        Long since;
        /** Set for an {@code {"events":[...]}} response. */
        boolean eventsShape;
        /** Terminal: stop polling. */
        AudDException error;
    }

    /**
     * Interpret one longpoll response body. The server answers with one of:
     * <ul>
     *   <li>{@code {"events":[{"timestamp":<ms>,"category":"…","data":<callback body>}, …]}}:
     *       each {@code data} (an object, or a JSON string holding one) is parsed
     *       like a callback POST body; the newest {@code timestamp} is the next
     *       since_time;</li>
     *   <li>{@code {"timeout":"no events before timeout","timestamp":<ms>}}:
     *       nothing new; the {@code timestamp} is the next since_time;</li>
     *   <li>{@code {"error":"…"}}: the request itself is invalid.</li>
     * </ul>
     * A callback body at the top level ({@code result} or {@code notification},
     * with an optional top-level {@code timestamp}) is accepted too. Anything
     * else yields an empty page.
     */
    static Page interpret(HttpResponse resp) {
        Page page = new Page();
        JsonNode body = resp.jsonBody();
        if (body == null || !body.isObject()) return page;

        JsonNode err = body.get("error");
        boolean hasError = err != null && !err.isNull();
        if (hasError || "error".equals(body.path("status").asText(""))) {
            page.error = errorFrom(resp, body, err);
            return page;
        }

        JsonNode events = body.get("events");
        if (events != null && !events.isNull()) {
            page.eventsShape = true;
            if (!events.isArray()) return page;
            for (JsonNode item : events) {
                if (item == null || !item.isObject()) continue;
                Long ts = timestampOf(item.get("timestamp"));
                if (ts != null && (page.since == null || ts > page.since)) page.since = ts;
                CallbackEvent parsed = parseEventData(item.get("data"));
                if (parsed != null) page.events.add(parsed);
            }
            return page;
        }

        if (body.has("result") || body.has("notification") || body.path("results").isArray()) {
            CallbackEvent parsed = parseEventData(body);
            if (parsed != null) page.events.add(parsed);
            page.since = timestampOf(body.get("timestamp"));
            return page;
        }

        if (body.has("timeout")) {
            page.since = timestampOf(body.get("timestamp"));
        }
        return page;
    }

    private static AudDException errorFrom(HttpResponse resp, JsonNode body, JsonNode err) {
        if (err != null && err.isObject()) {
            return ErrorMapping.buildFromErrorBody(body, resp.httpStatus(), resp.requestId(), false);
        }
        String message = err == null || err.isNull()
            ? "Longpoll request failed"
            : (err.isTextual() ? err.asText() : err.toString());
        return new AudDInvalidRequestError(0, message, resp.httpStatus(), resp.requestId(),
            Collections.emptyMap(), null, null, body);
    }

    /** Parse an event's data; {@code null} for data that doesn't parse or a recognition with no songs. */
    private static CallbackEvent parseEventData(JsonNode data) {
        if (data == null || data.isNull()) return null;
        if (data.isTextual()) {
            try {
                data = MAPPER.readTree(data.asText());
            } catch (IOException e) {
                return null;
            }
        }
        if (data == null || !data.isObject()) return null;
        CallbackEvent event;
        try {
            event = CallbackHelpers.parseCallback(data);
        } catch (RuntimeException e) {
            return null;
        }
        if (event.isMatch() && event.match().get().song() == null) return null;
        return event;
    }

    /** A millisecond timestamp, given as a number or a numeric string. */
    private static Long timestampOf(JsonNode node) {
        if (node == null) return null;
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isNumber()) return (long) node.asDouble();
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
