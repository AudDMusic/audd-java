package io.audd.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {
    private static final ObjectMapper M = new ObjectMapper();

    private HttpResponse ok() {
        try {
            return new HttpResponse(M.readTree("{\"status\":\"success\"}"), 200, null, "{\"status\":\"success\"}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse status(int code) {
        return new HttpResponse(null, code, null, "");
    }

    @Test
    void read_retriesOn500ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 3, 1);
        HttpResponse out = p.runSync(() -> {
            int n = calls.incrementAndGet();
            return n < 3 ? status(503) : ok();
        });
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void read_retriesOn429() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 3, 1);
        HttpResponse out = p.runSync(() -> {
            int n = calls.incrementAndGet();
            return n < 2 ? status(429) : ok();
        });
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void recognition_retriesOn5xxButNot429() throws Exception {
        // 429 is NOT retried for recognition (cost protection)
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.RECOGNITION, 3, 1);
        HttpResponse out = p.runSync(() -> {
            calls.incrementAndGet();
            return status(429);
        });
        assertThat(out.httpStatus()).isEqualTo(429);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void recognition_retriesOnConnectException() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.RECOGNITION, 3, 1);
        HttpResponse out = p.runSync(() -> {
            int n = calls.incrementAndGet();
            if (n < 2) throw new ConnectException("refused");
            return ok();
        });
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void mutating_doesNotRetryOn5xx() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.MUTATING, 3, 1);
        HttpResponse out = p.runSync(() -> {
            calls.incrementAndGet();
            return status(500);
        });
        assertThat(out.httpStatus()).isEqualTo(500);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void mutating_retriesOnConnectException() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.MUTATING, 3, 1);
        HttpResponse out = p.runSync(() -> {
            int n = calls.incrementAndGet();
            if (n < 2) throw new ConnectException("refused");
            return ok();
        });
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void exhaustingAttemptsReturnsLastResp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 2, 1);
        HttpResponse out = p.runSync(() -> {
            calls.incrementAndGet();
            return status(503);
        });
        assertThat(out.httpStatus()).isEqualTo(503);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void exhaustingAttemptsRethrows() {
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 2, 1);
        assertThatThrownBy(() -> p.runSync(() -> { throw new ConnectException("nope"); }))
            .isInstanceOf(ConnectException.class);
    }

    @Test
    void async_retriesOn5xxThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 3, 1);
        HttpResponse out = p.runAsync(() -> {
            int n = calls.incrementAndGet();
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            f.complete(n < 3 ? status(503) : ok());
            return f;
        }).get();
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void async_retriesOnIOException() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = new RetryPolicy(RetryClass.RECOGNITION, 3, 1);
        HttpResponse out = p.runAsync(() -> {
            int n = calls.incrementAndGet();
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            if (n < 2) {
                f.completeExceptionally(new ConnectException("refused"));
            } else {
                f.complete(ok());
            }
            return f;
        }).get();
        assertThat(out.httpStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void shouldRetryException_recognitionOnSocketTimeoutAfterUpload_returnsFalse() {
        // SocketTimeoutException without "connect" in message is post-upload.
        RetryPolicy p = new RetryPolicy(RetryClass.RECOGNITION, 3, 1);
        assertThat(p.shouldRetryException(new java.net.SocketTimeoutException("Read timed out"))).isFalse();
    }

    @Test
    void shouldRetryException_recognitionOnConnectTimeout_returnsTrue() {
        RetryPolicy p = new RetryPolicy(RetryClass.RECOGNITION, 3, 1);
        assertThat(p.shouldRetryException(new java.net.SocketTimeoutException("connect timed out"))).isTrue();
    }

    @Test
    void shouldRetryException_readOnAnyIOException_returnsTrue() {
        RetryPolicy p = new RetryPolicy(RetryClass.READ, 3, 1);
        assertThat(p.shouldRetryException(new IOException("anything"))).isTrue();
    }
}
