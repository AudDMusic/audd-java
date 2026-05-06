package io.audd.internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLHandshakeException;

/**
 * Holds retry config and runs retry loops (sync + async) over an HTTP
 * call closure.
 *
 * <p>Mirrors {@code audd-python/src/audd/_retry.py}.
 */
public final class RetryPolicy {
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_FLOOR = 500;

    private final RetryClass retryClass;
    private final int maxAttempts;
    private final long backoffFactorMs;
    private final long backoffMaxMs;
    private final Random random;

    public RetryPolicy(RetryClass retryClass, int maxAttempts, long backoffFactorMs, long backoffMaxMs) {
        this.retryClass = retryClass;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffFactorMs = Math.max(0, backoffFactorMs);
        this.backoffMaxMs = Math.max(this.backoffFactorMs, backoffMaxMs);
        this.random = new Random();
    }

    public RetryPolicy(RetryClass retryClass, int maxAttempts, long backoffFactorMs) {
        this(retryClass, maxAttempts, backoffFactorMs, 30_000);
    }

    public RetryClass retryClass() { return retryClass; }
    public int maxAttempts() { return maxAttempts; }
    public long backoffFactorMs() { return backoffFactorMs; }

    long backoffDelayMs(int attempt) {
        long base = Math.min(backoffFactorMs * (1L << attempt), backoffMaxMs);
        double jitter = 0.5 + random.nextDouble();
        return (long) (base * jitter);
    }

    public boolean shouldRetryResponse(int httpStatus) {
        switch (retryClass) {
            case READ:
                return httpStatus == HTTP_REQUEST_TIMEOUT
                        || httpStatus == HTTP_TOO_MANY_REQUESTS
                        || httpStatus >= HTTP_SERVER_ERROR_FLOOR;
            case RECOGNITION:
                return httpStatus >= HTTP_SERVER_ERROR_FLOOR;
            case MUTATING:
                return false;
            default:
                throw new AssertionError("unhandled RetryClass " + retryClass);
        }
    }

    public boolean shouldRetryException(Throwable exc) {
        Throwable e = unwrap(exc);
        switch (retryClass) {
            case READ:
                // Any IOException — connection refused, DNS, TLS, read or write
                // timeouts. READ is idempotent; safe to retry whatever happened.
                return e instanceof IOException;
            case RECOGNITION:
            case MUTATING:
                return isPreUploadConnectionError(e);
            default:
                throw new AssertionError("unhandled RetryClass " + retryClass);
        }
    }

    /**
     * Pre-upload connection errors are safe to retry on RECOGNITION/MUTATING:
     * if the request body never finished uploading, the server can't have
     * done any metered work yet.
     */
    static boolean isPreUploadConnectionError(Throwable e) {
        if (e instanceof ConnectException) return true;
        if (e instanceof UnknownHostException) return true;
        if (e instanceof SSLHandshakeException) return true;
        // SocketTimeoutException with "connect timed out" message is pre-upload.
        if (e instanceof SocketTimeoutException) {
            String msg = e.getMessage();
            return msg != null && msg.toLowerCase().contains("connect");
        }
        if (e instanceof InterruptedIOException) {
            String msg = e.getMessage();
            return msg != null && msg.toLowerCase().contains("connect");
        }
        return false;
    }

    static Throwable unwrap(Throwable e) {
        while ((e instanceof CompletionException || e.getClass() == RuntimeException.class)
                && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }

    /**
     * Run a closure that may either return an {@link HttpResponse} or throw
     * an {@link IOException}, applying retry logic per this policy.
     */
    public HttpResponse runSync(SyncCall call) throws IOException {
        IOException lastExc = null;
        HttpResponse lastResp = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpResponse resp;
            try {
                resp = call.call();
            } catch (IOException e) {
                lastExc = e;
                lastResp = null;
                if (!shouldRetryException(e)) {
                    throw e;
                }
                if (attempt + 1 >= maxAttempts) {
                    throw e;
                }
                sleep(backoffDelayMs(attempt));
                continue;
            }
            if (!shouldRetryResponse(resp.httpStatus())) {
                return resp;
            }
            lastResp = resp;
            lastExc = null;
            if (attempt + 1 >= maxAttempts) {
                return resp;
            }
            sleep(backoffDelayMs(attempt));
        }
        if (lastResp != null) return lastResp;
        if (lastExc != null) throw lastExc;
        throw new IllegalStateException("retry loop ended without result");
    }

    public CompletableFuture<HttpResponse> runAsync(Supplier<CompletableFuture<HttpResponse>> call) {
        CompletableFuture<HttpResponse> out = new CompletableFuture<>();
        attemptAsync(call, 0, out);
        return out;
    }

    private void attemptAsync(Supplier<CompletableFuture<HttpResponse>> call, int attempt, CompletableFuture<HttpResponse> out) {
        CompletableFuture<HttpResponse> step;
        try {
            step = call.get();
        } catch (Throwable t) {
            handleAsyncException(call, attempt, out, t);
            return;
        }
        step.whenComplete((resp, exc) -> {
            if (exc != null) {
                handleAsyncException(call, attempt, out, exc);
                return;
            }
            if (!shouldRetryResponse(resp.httpStatus())) {
                out.complete(resp);
                return;
            }
            if (attempt + 1 >= maxAttempts) {
                out.complete(resp);
                return;
            }
            scheduleNextAttempt(call, attempt, out);
        });
    }

    private void handleAsyncException(Supplier<CompletableFuture<HttpResponse>> call, int attempt, CompletableFuture<HttpResponse> out, Throwable exc) {
        Throwable cause = unwrap(exc);
        if (!shouldRetryException(cause)) {
            out.completeExceptionally(cause);
            return;
        }
        if (attempt + 1 >= maxAttempts) {
            out.completeExceptionally(cause);
            return;
        }
        scheduleNextAttempt(call, attempt, out);
    }

    private void scheduleNextAttempt(Supplier<CompletableFuture<HttpResponse>> call, int attempt, CompletableFuture<HttpResponse> out) {
        long delay = backoffDelayMs(attempt);
        CompletableFuture
                .delayedExecutor(delay, TimeUnit.MILLISECONDS)
                .execute(() -> attemptAsync(call, attempt + 1, out));
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sync call that may throw {@link IOException}. */
    @FunctionalInterface
    public interface SyncCall {
        HttpResponse call() throws IOException;
    }
}
