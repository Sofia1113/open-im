package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.ProductionProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsShortSecrets() {
        assertThatThrownBy(() -> new TokenService(objectMapper, "too-short", profile(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void rejectsTheDemoSecretInProductionMode() {
        assertThatThrownBy(() -> new TokenService(
                objectMapper,
                "open-im-local-demo-secret-do-not-use-in-production",
                profile(true)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used in production");
    }

    @Test
    void issuesAndVerifiesTokensWithAStrongSecret() {
        TokenService service = new TokenService(
                objectMapper,
                "test-only-token-secret-with-more-than-32-characters",
                profile(false)
        );

        var issued = service.issue("alice", "device-1", "access", 60);
        var payload = service.verify(issued.token(), "access");

        assertThat(payload).isPresent();
        assertThat(payload.orElseThrow().userId()).isEqualTo("alice");
        assertThat(service.verify(issued.token(), "refresh")).isEmpty();
    }

    private ProductionProfile profile(boolean active) {
        ProductionProfile profile = mock(ProductionProfile.class);
        when(profile.active()).thenReturn(active);
        return profile;
    }
}
