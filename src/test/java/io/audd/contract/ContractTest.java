package io.audd.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDApiError;
import io.audd.errors.AudDAuthenticationError;
import io.audd.errors.AudDBlockedError;
import io.audd.errors.AudDCustomCatalogAccessError;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.errors.AudDQuotaError;
import io.audd.errors.AudDSubscriptionError;
import io.audd.errors.ErrorMapping;
import io.audd.models.EnterpriseChunkResult;
import io.audd.models.EnterpriseMatch;
import io.audd.models.RecognitionResult;
import io.audd.models.StreamCallbackPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests against the shared OpenAPI fixtures. Verifies the Java parser
 * produces the right typed object for each canonical sample.
 *
 * <p>Fixtures live in {@code audd-openapi/fixtures} and are located via the
 * {@code AUDD_OPENAPI_FIXTURES} env var. CI sets that var to the checked-out
 * sibling repo path; local devs can {@code export AUDD_OPENAPI_FIXTURES=...}.
 * When the var is unset the whole class is skipped — see the {@code contract.yml}
 * workflow which explicitly sets it.
 */
@EnabledIfEnvironmentVariable(named = "AUDD_OPENAPI_FIXTURES", matches = ".+")
class ContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path fixtures() {
        String env = System.getenv("AUDD_OPENAPI_FIXTURES");
        if (env == null || env.isEmpty()) {
            throw new IllegalStateException("AUDD_OPENAPI_FIXTURES env var not set");
        }
        return Paths.get(env);
    }

    private static JsonNode load(String filename) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(fixtures().resolve(filename)));
    }

    private static RecognitionResult loadResult(String filename) throws IOException {
        JsonNode body = load(filename);
        return MAPPER.treeToValue(body.get("result"), RecognitionResult.class);
    }

    // ---- Recognition success bodies -----------------------------------------

    @Test
    void recognizeBasic_parses() throws Exception {
        RecognitionResult r = loadResult("recognize_basic.json");
        assertThat(r.artist()).isEqualTo("Tears For Fears");
        assertThat(r.title()).isEqualTo("Everybody Wants To Rule The World");
        assertThat(r.timecode()).isEqualTo("00:56");
        assertThat(r.songLink()).isEqualTo("https://lis.tn/NbkVb");
        assertThat(r.thumbnailUrl()).isEqualTo("https://lis.tn/NbkVb?thumb");
        assertThat(r.isPublicMatch()).isTrue();
        assertThat(r.isCustomMatch()).isFalse();
    }

    @Test
    void recognizeCustomMatch_parses() throws Exception {
        RecognitionResult r = loadResult("recognize_custom_match.json");
        assertThat(r.timecode()).isEqualTo("01:45");
        assertThat(r.audioId()).isEqualTo(146);
        assertThat(r.isCustomMatch()).isTrue();
        assertThat(r.thumbnailUrl()).isNull();
    }

    @Test
    void recognizeWithMetadata_appleAndSpotifyAndMusicBrainzParse() throws Exception {
        RecognitionResult r = loadResult("recognize_with_metadata.json");
        assertThat(r.appleMusic()).isNotNull();
        assertThat(r.appleMusic().isrc()).isEqualTo("GBUM71403885");
        assertThat(r.appleMusic().artistName()).isEqualTo("Tears for Fears");
        assertThat(r.spotify()).isNotNull();
        assertThat(r.spotify().id()).isEqualTo("5B9qVIyjqeWkeOAp2tJgqL");
        assertThat(r.musicbrainz()).isNotEmpty();
        // forward-compat: AppleMusic carries 'artwork', 'previews', etc. as
        // unmapped fields — they must round-trip via extras().
        assertThat(r.appleMusic().extras()).containsKey("artwork");
        assertThat(r.appleMusic().extras()).containsKey("previews");
    }

    // ---- Enterprise list-of-chunks shape ------------------------------------

    @Test
    void enterpriseWithIsrcUpc_parses() throws Exception {
        JsonNode body = load("enterprise_with_isrc_upc.json");
        List<EnterpriseChunkResult> chunks = MAPPER.readerForListOf(EnterpriseChunkResult.class)
                .readValue(body.get("result"));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).songs()).hasSize(1);
        EnterpriseMatch m = chunks.get(0).songs().get(0);
        assertThat(m.artist()).isEqualTo("Tears For Fears");
        assertThat(m.isrc()).isEqualTo("GBUM71403885");
        assertThat(m.upc()).isEqualTo("00602547037169");
    }

    @Test
    void getStreamsEmpty_returnsEmptyArray() throws Exception {
        JsonNode body = load("getStreams_empty.json");
        assertThat(body.get("status").asText()).isEqualTo("success");
        assertThat(body.get("result").isArray()).isTrue();
        assertThat(body.get("result").size()).isZero();
    }

    @Test
    void longpollNoEvents_hasTimeoutKey() throws Exception {
        JsonNode body = load("longpoll_no_events.json");
        assertThat(body.get("timeout").asText()).isEqualTo("no events before timeout");
    }

    // ---- Error code → exception mapping -------------------------------------

    private static AudDApiError errFromFixture(String name, boolean customCatalogContext) throws IOException {
        JsonNode body = load(name);
        assertThat(body.get("status").asText()).isEqualTo("error");
        return ErrorMapping.buildFromErrorBody(body, 200, null, customCatalogContext);
    }

    @Test
    void error900_authenticationException() throws Exception {
        AudDApiError exc = errFromFixture("error_900_invalid_token.json", false);
        assertThat(exc).isInstanceOf(AudDAuthenticationError.class);
        assertThat(exc.errorCode()).isEqualTo(900);
        assertThat(exc.requestedParams()).containsKey("api_token");
    }

    @Test
    void error902_quotaException() throws Exception {
        AudDApiError exc = errFromFixture("error_902_stream_limit.json", false);
        assertThat(exc).isInstanceOf(AudDQuotaError.class);
        assertThat(exc.errorCode()).isEqualTo(902);
    }

    @Test
    void error904_outsideCustomCatalog_subscriptionException() throws Exception {
        AudDApiError exc = errFromFixture("error_904_enterprise_unauthorized.json", false);
        assertThat(exc).isInstanceOf(AudDSubscriptionError.class);
        assertThat(exc).isNotInstanceOf(AudDCustomCatalogAccessError.class);
        // "requested_params" alt-spelling normalized to requestedParams().
        assertThat(exc.requestedParams()).containsKey("url");
    }

    @Test
    void error904_inCustomCatalogContext_overriddenException() throws Exception {
        AudDApiError exc = errFromFixture("error_904_enterprise_unauthorized.json", true);
        assertThat(exc).isInstanceOf(AudDCustomCatalogAccessError.class);
        assertThat(exc.serverMessage()).contains("Adding songs to your custom catalog");
    }

    @Test
    void error700_invalidRequest() throws Exception {
        AudDApiError exc = errFromFixture("error_700_no_file.json", false);
        assertThat(exc).isInstanceOf(AudDInvalidRequestError.class);
        assertThat(exc.errorCode()).isEqualTo(700);
    }

    @Test
    void error19_blocked() throws Exception {
        AudDApiError exc = errFromFixture("error_19_no_callback_url.json", false);
        assertThat(exc).isInstanceOf(AudDBlockedError.class);
        assertThat(exc.errorCode()).isEqualTo(19);
    }

    // ---- Streams callback payloads ------------------------------------------

    @Test
    void streamsCallbackResult_parses() throws Exception {
        JsonNode body = load("streams_callback_with_result.json");
        StreamCallbackPayload p = StreamCallbackPayload.parse(body);
        assertThat(p.isResult()).isTrue();
        assertThat(p.result().radioId()).isEqualTo(7);
        assertThat(p.result().playLength()).isEqualTo(111);
        assertThat(p.result().results()).hasSize(1);
        assertThat(p.result().results().get(0).artist()).isEqualTo("Alan Walker, A$AP Rocky");
    }

    @Test
    void streamsCallbackNotification_parses() throws Exception {
        JsonNode body = load("streams_callback_with_notification.json");
        StreamCallbackPayload p = StreamCallbackPayload.parse(body);
        assertThat(p.isNotification()).isTrue();
        assertThat(p.notification().radioId()).isEqualTo(3);
        assertThat(p.notification().notificationCode()).isEqualTo(650);
        assertThat(p.time()).isEqualTo(1587939136L);
    }
}
