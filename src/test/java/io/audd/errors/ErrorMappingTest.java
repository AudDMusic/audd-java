package io.audd.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMappingTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void code900_mapsToAuthenticationError() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":900,\"error_message\":\"bad token\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, "req-1", false);
        assertThat(exc).isInstanceOf(AudDAuthenticationError.class);
        assertThat(exc.errorCode()).isEqualTo(900);
        assertThat(exc.serverMessage()).isEqualTo("bad token");
        assertThat(exc.requestId()).isEqualTo("req-1");
        assertThat(exc.getMessage()).isEqualTo("[#900] bad token");
    }

    @Test
    void code904_inCustomCatalogContext_overridesMessage() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":904,\"error_message\":\"orig\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, true);
        assertThat(exc).isInstanceOf(AudDCustomCatalogAccessError.class);
        assertThat(exc.serverMessage()).contains("Adding songs to your custom catalog");
        assertThat(exc.serverMessage()).contains("[Server message: orig]");
    }

    @Test
    void code904_outsideCustomCatalog_isPlainSubscriptionError() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":904,\"error_message\":\"no enterprise\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
        assertThat(exc).isInstanceOf(AudDSubscriptionError.class);
        assertThat(exc).isNotInstanceOf(AudDCustomCatalogAccessError.class);
    }

    @Test
    void code700_mapsToInvalidRequest() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":700,\"error_message\":\"no file\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
        assertThat(exc).isInstanceOf(AudDInvalidRequestError.class);
    }

    @Test
    void code300_mapsToInvalidAudio() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":300,\"error_message\":\"too short\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDInvalidAudioError.class);
    }

    @Test
    void code611_mapsToRateLimit() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":611,\"error_message\":\"rate\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDRateLimitError.class);
    }

    @Test
    void code610_mapsToStreamLimit() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":610,\"error_message\":\"slots\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDStreamLimitError.class);
    }

    @Test
    void code907_mapsToNotReleased() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":907,\"error_message\":\"future\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDNotReleasedError.class);
    }

    @Test
    void code19And31337_mapToBlocked() throws Exception {
        JsonNode body19 = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":19,\"error_message\":\"banned\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body19, 200, null, false)).isInstanceOf(AudDBlockedError.class);
        JsonNode body31337 = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":31337,\"error_message\":\"abuse\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body31337, 200, null, false)).isInstanceOf(AudDBlockedError.class);
    }

    @Test
    void code20_mapsToNeedsUpdate() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":20,\"error_message\":\"upd\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDNeedsUpdateError.class);
    }

    @Test
    void code1000_mapsToServerError() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":1000,\"error_message\":\"oops\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDServerError.class);
    }

    @Test
    void unknownCode_fallsBackToServerError() throws Exception {
        JsonNode body = M.readTree("{\"status\":\"error\",\"error\":{\"error_code\":99999,\"error_message\":\"?\"}}");
        assertThat(ErrorMapping.buildFromErrorBody(body, 200, null, false)).isInstanceOf(AudDServerError.class);
    }

    @Test
    void brandedMessage_extractedFromErrorResult() throws Exception {
        JsonNode body = M.readTree(
            "{\"status\":\"error\",\"error\":{\"error_code\":31337,\"error_message\":\"abuse\"}," +
            "\"result\":{\"artist\":\"AudD\",\"title\":\"Sorry, your IP was banned\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
        assertThat(exc.brandedMessage()).contains("AudD").contains("Sorry, your IP was banned");
    }

    @Test
    void requestedParamsAndMethod_populatedFromBody() throws Exception {
        JsonNode body = M.readTree(
            "{\"status\":\"error\",\"error\":{\"error_code\":900,\"error_message\":\"bad\"}," +
            "\"request_params\":{\"api_token\":\"d***a\",\"url\":\"https://example.com\"}," +
            "\"request_api_method\":\"recognize\"}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
        assertThat(exc.requestedParams()).containsEntry("api_token", "d***a").containsEntry("url", "https://example.com");
        assertThat(exc.requestMethod()).isEqualTo("recognize");
    }

    @Test
    void requestedParamsAlternateSpellingHonored() throws Exception {
        JsonNode body = M.readTree(
            "{\"status\":\"error\",\"error\":{\"error_code\":904,\"error_message\":\"x\"}," +
            "\"requested_params\":{\"limit\":\"1\"}}");
        AudDApiError exc = ErrorMapping.buildFromErrorBody(body, 200, null, false);
        assertThat(exc.requestedParams()).containsEntry("limit", "1");
    }

    @Test
    void sealedHierarchy_assignmentTypeCheck() {
        AudDException base = new AudDSerializationError("test");
        assertThat(base).isInstanceOf(AudDException.class);
    }
}
