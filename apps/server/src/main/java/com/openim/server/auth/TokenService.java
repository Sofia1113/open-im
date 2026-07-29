package com.openim.server.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 签发与验证
 *
 * 使用标准 JWS（HS256）替代此前自研的 Base64+HMAC 拼接实现，以获得三项能力：
 * 令牌头部携带 {@code kid} 从而支持无停机密钥轮换、算法在验证时被显式约束
 * （拒绝 {@code alg=none} 与非对称降级攻击）、以及 {@code jti} 唯一标识用于撤销与审计。
 *
 * 令牌类型放在私有声明 {@code tt} 中，验证时强制匹配，
 * 避免刷新令牌被当作访问令牌直接使用。
 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;
    private static final String CLAIM_TOKEN_TYPE = "tt";
    private static final String CLAIM_DEVICE_ID = "did";

    /** 允许的时钟偏移，避免多实例之间的轻微不同步导致刚签发的令牌被拒 */
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final JwtKeySet keySet;
    private final String issuer;

    public TokenService(JwtKeySet keySet, @Value("${openim.jwt.issuer:open-im}") String issuer) {
        keySet.validate();
        this.keySet = keySet;
        this.issuer = issuer;
    }

    public IssuedToken issue(String userId, String deviceId, String type, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String tokenId = UUID.randomUUID().toString();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(userId)
                    .jwtID(tokenId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .claim(CLAIM_TOKEN_TYPE, type)
                    .claim(CLAIM_DEVICE_ID, deviceId)
                    .build();
            JWSHeader header = new JWSHeader.Builder(ALGORITHM)
                    .keyID(keySet.activeKeyId())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new MACSigner(keySet.activeSecret()));
            return new IssuedToken(jwt.serialize(), expiresAt, tokenId);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to issue authentication token", exception);
        }
    }

    /**
     * 验证令牌并返回声明。
     *
     * 任何失败路径都返回空而不抛异常，且不记录令牌内容——
     * 区分"签名错误"与"已过期"会给攻击者提供额外信息。
     */
    public Optional<TokenPayload> verify(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            // 算法必须与签发时一致：不接受令牌自称的任意算法
            if (!ALGORITHM.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            byte[] secret = keySet.secretForKeyId(jwt.getHeader().getKeyID()).orElse(null);
            if (secret == null) {
                return Optional.empty();
            }
            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer())
                    || !expectedType.equals(claims.getStringClaim(CLAIM_TOKEN_TYPE))
                    || claims.getSubject() == null
                    || claims.getStringClaim(CLAIM_DEVICE_ID) == null) {
                return Optional.empty();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null
                    || expiration.toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new TokenPayload(
                    claims.getSubject(),
                    claims.getStringClaim(CLAIM_DEVICE_ID),
                    expectedType,
                    expiration.toInstant().getEpochSecond(),
                    claims.getJWTID()
            ));
        } catch (Exception exception) {
            log.debug("Rejected malformed or unverifiable token");
            return Optional.empty();
        }
    }

    public record TokenPayload(
            String userId,
            String deviceId,
            String type,
            long exp,
            String tokenId
    ) {
    }

    public record IssuedToken(String token, Instant expiresAt, String tokenId) {
    }
}
