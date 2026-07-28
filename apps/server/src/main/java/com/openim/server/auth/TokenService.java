package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.ProductionProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Token 签发与验证服务
 * 使用 HMAC-SHA256 对 JSON 负载签名，生成 access_token 和 refresh_token。
 */
@Service
public class TokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public TokenService(
            ObjectMapper objectMapper,
            @Value("${openim.token-secret}") String secret,
            ProductionProfile productionProfile
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("TOKEN_SECRET must contain at least 32 characters");
        }
        if (productionProfile.active() && secret.contains("do-not-use-in-production")) {
            throw new IllegalStateException("The local demo TOKEN_SECRET cannot be used in production");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public IssuedToken issue(
            String userId,
            String deviceId,
            String type,
            long ttlSeconds
    ) {
        try {
            Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
            TokenPayload payload = new TokenPayload(
                    userId,
                    deviceId,
                    type,
                    expiresAt.getEpochSecond(),
                    UUID.randomUUID().toString()
            );
            String encoded = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            String signature = ENCODER.encodeToString(sign(encoded));
            return new IssuedToken(encoded + "." + signature, expiresAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to issue authentication token", exception);
        }
    }

    public Optional<TokenPayload> verify(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            byte[] providedSignature = DECODER.decode(parts[1]);
            byte[] expectedSignature = sign(parts[0]);
            if (!MessageDigest.isEqual(providedSignature, expectedSignature)) {
                return Optional.empty();
            }
            TokenPayload payload = objectMapper.readValue(
                    DECODER.decode(parts[0]),
                    TokenPayload.class
            );
            if (!expectedType.equals(payload.type()) ||
                    payload.exp() <= Instant.now().getEpochSecond() ||
                    payload.userId() == null || payload.deviceId() == null) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] sign(String encodedPayload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenPayload(
            String userId,
            String deviceId,
            String type,
            long exp,
            String nonce
    ) {
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
