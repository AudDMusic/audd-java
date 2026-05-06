package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.models.StreamCallbackPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackHelpersTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void deriveLongpollCategory_isStableAndNineChars() {
        String c = CallbackHelpers.deriveLongpollCategory("test-token", 42);
        assertThat(c).hasSize(9);
        // recompute — must be deterministic
        assertThat(CallbackHelpers.deriveLongpollCategory("test-token", 42)).isEqualTo(c);
    }

    @Test
    void deriveLongpollCategory_differentTokenChangesCategory() {
        String a = CallbackHelpers.deriveLongpollCategory("token-a", 1);
        String b = CallbackHelpers.deriveLongpollCategory("token-b", 1);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void deriveLongpollCategory_differentRadioIdChangesCategory() {
        String a = CallbackHelpers.deriveLongpollCategory("token", 1);
        String b = CallbackHelpers.deriveLongpollCategory("token", 2);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void addReturnToUrl_appendsParam() {
        String url = CallbackHelpers.addReturnToUrl("https://hooks.example.com/cb", "apple_music");
        assertThat(url).isEqualTo("https://hooks.example.com/cb?return=apple_music");
    }

    @Test
    void addReturnToUrl_listJoinedWithComma() {
        String url = CallbackHelpers.addReturnToUrl("https://hooks.example.com/cb", List.of("apple_music", "spotify"));
        assertThat(url).contains("return=apple_music%2Cspotify");
    }

    @Test
    void addReturnToUrl_nullMetadataReturnsUrlUnchanged() {
        String url = CallbackHelpers.addReturnToUrl("https://hooks.example.com/cb", (String) null);
        assertThat(url).isEqualTo("https://hooks.example.com/cb");
    }

    @Test
    void addReturnToUrl_existingReturnQueryThrows() {
        assertThatThrownBy(() ->
            CallbackHelpers.addReturnToUrl("https://hooks.example.com/cb?return=spotify", "apple_music"))
            .isInstanceOf(AudDInvalidRequestError.class)
            .hasMessageContaining("already contains");
    }

    @Test
    void addReturnToUrl_preservesOtherQueryParams() {
        String url = CallbackHelpers.addReturnToUrl("https://hooks.example.com/cb?foo=1&bar=2", "spotify");
        assertThat(url).contains("foo=1").contains("bar=2").contains("return=spotify");
    }

    @Test
    void parseCallback_dispatchesToPayload() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"-\",\"notification\":{\"radio_id\":1,\"notification_code\":650,\"notification_message\":\"x\"},\"time\":1}");
        StreamCallbackPayload p = CallbackHelpers.parseCallback(body);
        assertThat(p.isNotification()).isTrue();
    }
}
