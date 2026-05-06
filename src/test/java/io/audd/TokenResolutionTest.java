package io.audd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token-resolution contract: explicit arg wins, then {@code AUDD_API_TOKEN} env
 * var, then a clear {@link IllegalArgumentException} that points at the
 * dashboard. See design spec §7.11.
 */
class TokenResolutionTest {

    @Test
    void explicitTokenWinsOverEnv() {
        // Builder with explicit token always works regardless of env state — we
        // don't mutate the system env from tests; explicit-arg precedence is
        // the contract.
        AudD a = AudD.builder().apiToken("explicit-token").build();
        try {
            assertThat(a.apiToken()).isEqualTo("explicit-token");
        } finally {
            a.close();
        }
    }

    @Test
    void emptyTokenAndNoEnvRaisesIllegalArgument() {
        // Most CI environments don't set AUDD_API_TOKEN; this assertion only
        // runs when the env var is *not* set, otherwise we skip silently rather
        // than fail spuriously on a developer machine that has it exported.
        if (System.getenv(AudD.API_TOKEN_ENV_VAR) != null) return;
        assertThatThrownBy(() -> AudD.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard.audd.io")
                .hasMessageContaining(AudD.API_TOKEN_ENV_VAR);
    }

    @Test
    void fromEnvironmentDelegatesToEnvLookup() {
        if (System.getenv(AudD.API_TOKEN_ENV_VAR) != null) return;
        assertThatThrownBy(AudD::fromEnvironment)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AudD.API_TOKEN_ENV_VAR);
    }

    @Test
    void asyncFromEnvironmentDelegatesToEnvLookup() {
        if (System.getenv(AudD.API_TOKEN_ENV_VAR) != null) return;
        assertThatThrownBy(AsyncAudD::fromEnvironment)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
