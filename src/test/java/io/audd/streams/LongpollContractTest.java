package io.audd.streams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.audd.errors.AudDInvalidRequestError;
import io.audd.internal.HttpResponse;
import io.audd.models.StreamCallbackMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The longpoll response interpreter against the shared {@code audd-openapi}
 * fixtures, located like {@link io.audd.contract.ContractTest} does via
 * {@code AUDD_OPENAPI_FIXTURES}.
 */
@EnabledIfEnvironmentVariable(named = "AUDD_OPENAPI_FIXTURES", matches = ".+")
class LongpollContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LongpollPoll.Page interpret(String filename) throws Exception {
        Path path = Paths.get(System.getenv("AUDD_OPENAPI_FIXTURES")).resolve(filename);
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        JsonNode body = MAPPER.readTree(text);
        return LongpollPoll.interpret(new HttpResponse(body, 200, null, text));
    }

    @Test
    void events_yieldBothMatchesInOrder_andTheNewestTimestamp() throws Exception {
        LongpollPoll.Page page = interpret("longpoll_events.json");
        assertThat(page.error).isNull();
        assertThat(page.events).hasSize(2);
        StreamCallbackMatch first = page.events.get(0).match().orElseThrow();
        StreamCallbackMatch second = page.events.get(1).match().orElseThrow();
        assertThat(first.radioId()).isEqualTo(7L);
        assertThat(first.song().artist()).isEqualTo("Alan Walker, A$AP Rocky");
        assertThat(first.song().title()).isEqualTo("Live Fast (PUBGM)");
        assertThat(second.song().artist()).isEqualTo("Tears For Fears");
        assertThat(second.song().title()).isEqualTo("Everybody Wants To Rule The World");
        assertThat(page.since).isEqualTo(1795547548112L);
    }

    @Test
    void noEvents_yieldsNothing_butItsTimestamp() throws Exception {
        LongpollPoll.Page page = interpret("longpoll_no_events.json");
        assertThat(page.error).isNull();
        assertThat(page.events).isEmpty();
        assertThat(page.since).isEqualTo(1777901270049L);
    }

    @Test
    void error_isAnInvalidRequestCarryingTheServerText() throws Exception {
        LongpollPoll.Page page = interpret("longpoll_error.json");
        assertThat(page.error).isInstanceOfSatisfying(AudDInvalidRequestError.class,
            e -> assertThat(e.serverMessage()).startsWith("Invalid timeout arg."));
        assertThat(page.events).isEmpty();
    }
}
