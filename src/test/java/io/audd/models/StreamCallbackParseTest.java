package io.audd.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDSerializationError;
import io.audd.streams.CallbackHelpers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamCallbackParseTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void resultPayload_parsesAsMatch() throws Exception {
        String json = "{\"status\":\"success\",\"result\":{\"radio_id\":7,\"timestamp\":\"2020-04-13 10:31:43\","
                + "\"play_length\":111,\"results\":[{\"artist\":\"Alan Walker\",\"title\":\"Live Fast\",\"score\":100}]}}";
        JsonNode body = M.readTree(json);
        CallbackEvent event = CallbackHelpers.parseCallback(body);
        assertThat(event.isMatch()).isTrue();
        assertThat(event.isNotification()).isFalse();
        StreamCallbackMatch match = event.match().orElseThrow();
        assertThat(match.radioId()).isEqualTo(7L);
        assertThat(match.timestamp()).isEqualTo("2020-04-13 10:31:43");
        assertThat(match.playLength()).isEqualTo(111);
        assertThat(match.song()).isNotNull();
        assertThat(match.song().artist()).isEqualTo("Alan Walker");
        assertThat(match.song().title()).isEqualTo("Live Fast");
        assertThat(match.alternatives()).isEmpty();
    }

    @Test
    void resultPayload_multipleSongs_firstIsTopMatchRestAreAlternatives() throws Exception {
        String json = "{\"result\":{\"radio_id\":1,\"results\":["
                + "{\"artist\":\"A\",\"title\":\"X\",\"score\":100},"
                + "{\"artist\":\"B\",\"title\":\"X\",\"score\":90},"
                + "{\"artist\":\"C\",\"title\":\"X (remix)\",\"score\":85}"
                + "]}}";
        JsonNode body = M.readTree(json);
        CallbackEvent event = CallbackHelpers.parseCallback(body);
        StreamCallbackMatch match = event.match().orElseThrow();
        assertThat(match.song().artist()).isEqualTo("A");
        assertThat(match.alternatives()).hasSize(2);
        // alternatives may legitimately have a different artist/title from the
        // top song — variant catalog releases / near-duplicate fingerprints.
        assertThat(match.alternatives().get(0).artist()).isEqualTo("B");
        assertThat(match.alternatives().get(1).title()).isEqualTo("X (remix)");
    }

    @Test
    void notificationPayload_parsesWithOuterTime() throws Exception {
        String json = "{\"status\":\"-\",\"notification\":{\"radio_id\":3,\"stream_running\":false,"
                + "\"notification_code\":650,\"notification_message\":\"oops\"},\"time\":1587939136}";
        JsonNode body = M.readTree(json);
        CallbackEvent event = CallbackHelpers.parseCallback(body);
        assertThat(event.isNotification()).isTrue();
        StreamCallbackNotification n = event.notification().orElseThrow();
        assertThat(n.radioId()).isEqualTo(3);
        assertThat(n.streamRunning()).isFalse();
        assertThat(n.notificationCode()).isEqualTo(650);
        assertThat(n.notificationMessage()).isEqualTo("oops");
        assertThat(n.time()).isEqualTo(1587939136L);
    }

    @Test
    void parseCallback_bytesOverloadWorks() {
        String json = "{\"status\":\"-\",\"notification\":{\"radio_id\":1,\"notification_code\":650,\"notification_message\":\"x\"},\"time\":1}";
        CallbackEvent event = CallbackHelpers.parseCallback(json.getBytes(StandardCharsets.UTF_8));
        assertThat(event.isNotification()).isTrue();
    }

    @Test
    void parseCallback_inputStreamOverloadWorks() {
        String json = "{\"result\":{\"radio_id\":1,\"results\":[{\"artist\":\"a\",\"title\":\"t\"}]}}";
        CallbackEvent event = CallbackHelpers.parseCallback(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(event.isMatch()).isTrue();
        assertThat(event.match().orElseThrow().song().artist()).isEqualTo("a");
    }

    @Test
    void parseCallback_invalidJson_raisesSerializationError() {
        assertThatThrownBy(() -> CallbackHelpers.parseCallback("not json".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(AudDSerializationError.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    void parseCallback_emptyResults_raisesSerializationError() throws Exception {
        JsonNode body = M.readTree("{\"result\":{\"radio_id\":1,\"results\":[]}}");
        assertThatThrownBy(() -> CallbackHelpers.parseCallback(body))
            .isInstanceOf(AudDSerializationError.class)
            .hasMessageContaining("results is empty");
    }

    @Test
    void parseCallback_neitherMatchNorNotification_raisesSerializationError() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"success\"}");
        assertThatThrownBy(() -> CallbackHelpers.parseCallback(body))
            .isInstanceOf(AudDSerializationError.class)
            .hasMessageContaining("neither result nor notification");
    }

    @Test
    void parseCallback_topLevelResults_treatedAsMatch() throws Exception {
        // Some longpoll envelopes put the match fields at the top level
        // without wrapping them in a `result` object.
        JsonNode body = M.readTree(
            "{\"radio_id\":1,\"timestamp\":\"2020-04-13 10:31:43\",\"results\":[{\"artist\":\"a\",\"title\":\"t\"}]}");
        CallbackEvent event = CallbackHelpers.parseCallback(body);
        assertThat(event.isMatch()).isTrue();
        assertThat(event.match().orElseThrow().song().title()).isEqualTo("t");
    }
}
