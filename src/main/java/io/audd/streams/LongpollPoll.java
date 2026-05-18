package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDConnectionError;
import io.audd.errors.AudDSerializationError;
import io.audd.errors.AudDServerError;
import io.audd.internal.HttpClient;
import io.audd.internal.HttpResponse;
import io.audd.internal.RetryPolicy;
import io.audd.models.CallbackEvent;
import io.audd.models.StreamCallbackMatch;
import io.audd.models.StreamCallbackNotification;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>The error callback is single-shot: when it fires, the loop exits and
 * no more match/notification callbacks will be invoked. Calling
 * {@link #close()} is idempotent and shuts the loop down cleanly without
 * surfacing an error.</p>
 */
public final class LongpollPoll implements AutoCloseable {
    private final HttpClient http;
    private final RetryPolicy readPolicy;
    private final String apiBase;
    private final String category;
    private final int timeout;
    private Long currentSince;

    private volatile Consumer<StreamCallbackMatch> onMatch = m -> {};
    private volatile Consumer<StreamCallbackNotification> onNotification = n -> {};
    private volatile Consumer<Throwable> onError = e -> {};

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    LongpollPoll(HttpClient http, RetryPolicy readPolicy, String apiBase,
                 String category, LongpollOptions opts) {
        this.http = http;
        this.readPolicy = readPolicy;
        this.apiBase = apiBase;
        this.category = category;
        this.timeout = opts.timeout();
        this.currentSince = opts.sinceTime();
    }

    /** Register the per-match callback. Replaces any previous registration. */
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
     * poll is already running on another thread.
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
     * that completes when the loop exits (normally on close, or
     * exceptionally if an error callback rethrows).
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

    /** Stop the poll. Idempotent. Safe to call from any thread. */
    @Override
    public void close() {
        stopped.set(true);
    }

    /** True after {@link #close()} has been invoked. */
    public boolean isClosed() { return stopped.get(); }

    private void loop() {
        while (!stopped.get()) {
            HttpResponse resp;
            try {
                Map<String, String> params = new HashMap<>();
                params.put("category", category);
                params.put("timeout", String.valueOf(timeout));
                if (currentSince != null) params.put("since_time", String.valueOf(currentSince));
                resp = readPolicy.runSync(() -> http.get(apiBase + "/longpoll/", params));
            } catch (IOException e) {
                if (stopped.get()) return;
                onError.accept(new AudDConnectionError(
                    e.getMessage() == null ? "connection error" : e.getMessage(), e));
                return;
            } catch (AudDApiError e) {
                if (stopped.get()) return;
                onError.accept(e);
                return;
            } catch (RuntimeException e) {
                if (stopped.get()) return;
                onError.accept(e);
                return;
            }

            if (stopped.get()) return;

            if (resp.httpStatus() >= 400) {
                onError.accept(new AudDServerError(
                    0, "Longpoll endpoint returned HTTP " + resp.httpStatus(),
                    resp.httpStatus(), resp.requestId(),
                    java.util.Collections.emptyMap(), null, null, resp.jsonBody()));
                return;
            }

            JsonNode body = resp.jsonBody();
            if (body == null || !body.isObject()) {
                onError.accept(new AudDSerializationError(
                    "Unparseable longpoll response", resp.rawText()));
                return;
            }

            // Update the timestamp cursor — used as `since_time` on the next poll.
            JsonNode ts = body.path("timestamp");
            if (ts.isNumber()) currentSince = ts.asLong();

            // Empty-window envelope: {"timeout": "no events before timeout", "timestamp": ...}.
            // Skip and re-poll.
            if (body.has("timeout") && !body.has("notification") && !body.has("result")) {
                continue;
            }

            CallbackEvent event;
            try {
                event = CallbackHelpers.parseCallback(body);
            } catch (RuntimeException e) {
                onError.accept(e);
                return;
            }

            if (event.isMatch()) {
                try {
                    onMatch.accept(event.match().get());
                } catch (RuntimeException userExc) {
                    onError.accept(userExc);
                    return;
                }
            } else if (event.isNotification()) {
                try {
                    onNotification.accept(event.notification().get());
                } catch (RuntimeException userExc) {
                    onError.accept(userExc);
                    return;
                }
            }
        }
    }
}
