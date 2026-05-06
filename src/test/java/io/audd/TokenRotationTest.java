package io.audd;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AudD#setApiToken(String)} thread-safe rotation: replacement is visible
 * to subsequent requests and to sub-clients (Streams). See design spec §7.10.
 */
class TokenRotationTest {

    @Test
    void setApiToken_replacesTokenForSubsequentRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // Two enqueued responses; capture each request to verify token used.
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));

            try (AudD a = AudD.builder()
                    .apiToken("orig")
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .build()) {
                a.recognize("https://example.com/audio.mp3");
                assertThat(a.apiToken()).isEqualTo("orig");
                a.setApiToken("rotated");
                assertThat(a.apiToken()).isEqualTo("rotated");
                a.recognize("https://example.com/audio.mp3");
            }

            String first = server.takeRequest().getBody().readUtf8();
            String second = server.takeRequest().getBody().readUtf8();
            assertThat(first).contains("api_token").contains("orig");
            assertThat(first).doesNotContain("rotated");
            assertThat(second).contains("api_token").contains("rotated");
            assertThat(second).doesNotContain("\"orig\"");
        }
    }

    @Test
    void setApiToken_rejectsNullAndEmpty() {
        try (AudD a = AudD.builder().apiToken("orig").build()) {
            assertThatThrownBy(() -> a.setApiToken(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> a.setApiToken("")).isInstanceOf(IllegalArgumentException.class);
            assertThat(a.apiToken()).isEqualTo("orig");
        }
    }

    @Test
    void setApiToken_visibleToStreamsLongpollCategory() {
        try (AudD a = AudD.builder().apiToken("orig").build()) {
            String catBefore = a.streams().deriveLongpollCategory(42);
            a.setApiToken("rotated");
            String catAfter = a.streams().deriveLongpollCategory(42);
            // Different token + same radio_id = different category. The Streams
            // sub-client must observe the rotation (Supplier-based pickup).
            assertThat(catAfter).isNotEqualTo(catBefore);
        }
    }

    @Test
    void async_setApiToken_replacesTokenForSubsequentRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));
            server.enqueue(new MockResponse().setBody("{\"status\":\"success\",\"result\":null}"));

            try (AsyncAudD a = AudD.builder()
                    .apiToken("orig")
                    .apiBase(server.url("/").toString().replaceAll("/$", ""))
                    .maxRetries(1).backoffFactorMs(1)
                    .buildAsync()) {
                a.recognize("https://example.com/a.mp3").get();
                a.setApiToken("rotated");
                a.recognize("https://example.com/a.mp3").get();
            }

            String first = server.takeRequest().getBody().readUtf8();
            String second = server.takeRequest().getBody().readUtf8();
            assertThat(first).contains("orig");
            assertThat(second).contains("rotated");
        }
    }
}
