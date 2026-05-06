package io.audd.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamCallbackPayloadTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void resultPayload_parsesAsResult() throws Exception {
        String json = "{\"status\":\"success\",\"result\":{\"radio_id\":7,\"timestamp\":\"2020-04-13 10:31:43\","
                + "\"play_length\":111,\"results\":[{\"artist\":\"Alan Walker\",\"title\":\"Live Fast\",\"score\":100}]}}";
        JsonNode body = M.readTree(json);
        StreamCallbackPayload p = StreamCallbackPayload.parse(body);
        assertThat(p.isResult()).isTrue();
        assertThat(p.isNotification()).isFalse();
        assertThat(p.result().radioId()).isEqualTo(7);
        assertThat(p.result().results()).hasSize(1);
        assertThat(p.result().results().get(0).artist()).isEqualTo("Alan Walker");
    }

    @Test
    void notificationPayload_parsesAsNotification() throws Exception {
        String json = "{\"status\":\"-\",\"notification\":{\"radio_id\":3,\"stream_running\":false,"
                + "\"notification_code\":650,\"notification_message\":\"oops\"},\"time\":1587939136}";
        JsonNode body = M.readTree(json);
        StreamCallbackPayload p = StreamCallbackPayload.parse(body);
        assertThat(p.isNotification()).isTrue();
        assertThat(p.isResult()).isFalse();
        assertThat(p.notification().radioId()).isEqualTo(3);
        assertThat(p.notification().notificationCode()).isEqualTo(650);
        assertThat(p.time()).isEqualTo(1587939136L);
    }
}
