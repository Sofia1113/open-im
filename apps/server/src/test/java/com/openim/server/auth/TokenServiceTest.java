package com.openim.server.auth;

import com.openim.server.config.ProductionProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {
    private static final String STRONG_SECRET = "test-only-token-secret-with-more-than-32-characters";
    private static final String OTHER_SECRET = "another-test-token-secret-with-over-32-characters";

    @Test
    void rejectsShortSecrets() {
        assertThatThrownBy(() -> service(false, "k1", Map.of("k1", "too-short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void rejectsTheDemoSecretInProductionMode() {
        assertThatThrownBy(() -> service(true, "k1",
                Map.of("k1", "open-im-local-demo-secret-do-not-use-in-production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used in production");
    }

    @Test
    void rejectsAnActiveKeyIdThatIsNotConfigured() {
        assertThatThrownBy(() -> service(false, "missing", Map.of("k1", STRONG_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-id");
    }

    @Test
    void issuesAndVerifiesTokensWithAStrongSecret() {
        TokenService service = service(false, "k1", Map.of("k1", STRONG_SECRET));

        var issued = service.issue("alice", "device-1", "access", 60);
        var payload = service.verify(issued.token(), "access");

        assertThat(payload).isPresent();
        assertThat(payload.orElseThrow().userId()).isEqualTo("alice");
        assertThat(payload.orElseThrow().deviceId()).isEqualTo("device-1");
        assertThat(payload.orElseThrow().tokenId()).isNotBlank();
    }

    @Test
    void rejectsATokenPresentedAsTheWrongType() {
        TokenService service = service(false, "k1", Map.of("k1", STRONG_SECRET));

        var refreshToken = service.issue("alice", "device-1", "refresh", 60);

        // 刷新令牌不得当作访问令牌使用
        assertThat(service.verify(refreshToken.token(), "access")).isEmpty();
        assertThat(service.verify(refreshToken.token(), "refresh")).isPresent();
    }

    @Test
    void rejectsExpiredTokens() {
        TokenService service = service(false, "k1", Map.of("k1", STRONG_SECRET));

        // 负 TTL 让令牌的过期时间早于允许的时钟偏移
        var expired = service.issue("alice", "device-1", "access", -120);

        assertThat(service.verify(expired.token(), "access")).isEmpty();
    }

    @Test
    void rejectsTokensSignedWithAnUnknownKey() {
        TokenService issuer = service(false, "k1", Map.of("k1", STRONG_SECRET));
        TokenService verifier = service(false, "k2", Map.of("k2", OTHER_SECRET));

        var issued = issuer.issue("alice", "device-1", "access", 60);

        assertThat(verifier.verify(issued.token(), "access")).isEmpty();
    }

    @Test
    void acceptsTokensSignedWithARetiredKeyDuringRotation() {
        Map<String, String> bothKeys = new LinkedHashMap<>();
        bothKeys.put("k1", STRONG_SECRET);
        bothKeys.put("k2", OTHER_SECRET);

        TokenService beforeRotation = service(false, "k1", Map.of("k1", STRONG_SECRET));
        TokenService afterRotation = service(false, "k2", bothKeys);

        var oldToken = beforeRotation.issue("alice", "device-1", "access", 60);
        var newToken = afterRotation.issue("alice", "device-1", "access", 60);

        // 轮换期间：旧密钥签发的令牌仍能验证，新令牌用新密钥签发
        assertThat(afterRotation.verify(oldToken.token(), "access")).isPresent();
        assertThat(afterRotation.verify(newToken.token(), "access")).isPresent();
    }

    private TokenService service(boolean production, String activeKeyId, Map<String, String> keys) {
        ProductionProfile profile = mock(ProductionProfile.class);
        when(profile.active()).thenReturn(production);
        JwtKeySet keySet = new JwtKeySet(profile);
        keySet.setKeys(keys);
        keySet.setActiveKeyId(activeKeyId);
        return new TokenService(keySet, "open-im");
    }
}
