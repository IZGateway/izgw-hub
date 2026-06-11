package gov.cdc.izgateway.hub.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.principal.InvalidJwtTokenException;
import gov.cdc.izgateway.security.principal.JwtTokenExtractor;
import gov.cdc.izgateway.utils.SystemUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ApiKeyPrincipalProvider {

    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofDays(366);
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final JwtConfig config;
    private final ApiKeyCredentialRepository credentialRepository;
    private final JwtTokenExtractor jwtTokenExtractor;
    private final SecretsManagerClient secretsManagerClient;

    private final Cache<String, String> secretCache;
    private final Cache<String, Object> credentialCache;

    @Autowired
    public ApiKeyPrincipalProvider(
            JwtConfig config,
            ApiKeyCredentialRepository credentialRepository,
            JwtTokenExtractor jwtTokenExtractor,
            @Autowired(required = false) SecretsManagerClient secretsManagerClient
    ) {
        this.config = config;
        this.credentialRepository = credentialRepository;
        this.jwtTokenExtractor = jwtTokenExtractor;
        this.secretsManagerClient = secretsManagerClient;

        this.secretCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getSecretCacheTtl())
                .build();

        this.credentialCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Object>() {
                    @Override
                    public long expireAfterCreate(String key, Object value, long currentTime) {
                        if (Boolean.TRUE.equals(value)) {
                            return TimeUnit.NANOSECONDS.convert(MAX_TOKEN_LIFETIME);
                        }
                        return config.getCredentialCacheTtl().toNanos();
                    }
                    @Override
                    public long expireAfterUpdate(String key, Object value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                    @Override
                    public long expireAfterRead(String key, Object value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    public IzgPrincipal getProvider(HttpServletRequest request) {
        log.info("getProvider 1");
        // Step 1: Extract Bearer token — return null if absent (fallback to cert auth)
        String token;
        try {
            token = jwtTokenExtractor.extractToken(request);
        } catch (InvalidJwtTokenException e) {
            log.info("No Bearer token in request from {}", request.getRemoteAddr());
            return null;
        }

        // Step 2: Parse JWT header only
        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            log.info("Failed to parse JWT token: {}", e.getMessage());
            return null;
        }

        // Step 3: Pre-check alg and iss before expensive operations
        if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())) {
            log.info("JWT rejected: unsupported algorithm={}", signedJwt.getHeader().getAlgorithm());
            return null;
        }

        JWTClaimsSet unverifiedClaims;
        try {
            unverifiedClaims = signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return null;
        }

        if (!config.getIssuer().equals(unverifiedClaims.getIssuer())) {
            log.info("JWT rejected: issuer mismatch (expected={}, got={})", config.getIssuer(), unverifiedClaims.getIssuer());
            return null;
        }

        // Step 4: Resolve signing secret by kid
        String kid = signedJwt.getHeader().getKeyID();
        byte[] secretBytes = resolveSecret(kid);
        if (secretBytes == null) {
            return null;
        }

        // Step 5: Verify HS256 signature
        try {
            if (!signedJwt.verify(new MACVerifier(secretBytes))) {
                log.warn("JWT signature verification failed for kid={}", kid);
                return null;
            }
        } catch (JOSEException e) {
            log.warn("JWT verification exception for kid={}: {}", kid, e.getMessage());
            return null;
        }

        // Step 6: Validate claims against verified payload
        JWTClaimsSet claims = unverifiedClaims; // already parsed from verified JWT
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().isBefore(Instant.now().minusSeconds(CLOCK_SKEW_SECONDS))) {
            log.trace("JWT expired: exp={}", expiry);
            return null;
        }

        String env = (String) claims.getClaim("env");
        if (!SystemUtils.getDestTypeAsString().equals(env)) {
            log.trace("JWT env mismatch: token env={}, hub env={}", env, SystemUtils.getDestTypeAsString());
            return null;
        }

        if (!config.getIssuer().equals(claims.getIssuer())) {
            return null;
        }

        // Step 7: Credential cache lookup by jti
        String jti = claims.getJWTID();
        if (jti == null) {
            return null;
        }

        Object cached = credentialCache.getIfPresent(jti);
        if (cached != null) {
            if (Boolean.TRUE.equals(cached)) {
                log.trace("JWT jti={} is in REVOKED cache", jti);
                return null;
            }
            return (ApiKeyPrincipal) cached;
        }

        log.info("getProvider 10");

        // Step 8: DynamoDB lookup on cache miss
        return lookupAndCacheCredential(claims, env, jti);
    }

    private IzgPrincipal lookupAndCacheCredential(JWTClaimsSet claims, String env, String jti) {
        var credentialOpt = credentialRepository.findByEnvAndJti(env, jti);

        if (credentialOpt.isPresent() && "active".equals(credentialOpt.get().getStatus())) {
            String sub = claims.getSubject();
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) claims.getClaim("roles");
            String dns = (String) claims.getClaim("dns");

            ApiKeyPrincipal principal = new ApiKeyPrincipal(
                    sub,
                    jti,
                    roles != null ? roles : Collections.emptyList(),
                    dns,
                    config.getIssuer()
            );
            credentialCache.put(jti, principal);
            log.debug("Authenticated ApiKeyPrincipal jti={} org={}", jti, sub);
            return principal;
        }

        // Revoked, expired, or absent — cache REVOKED sentinel with max-token-lifetime TTL
        credentialCache.put(jti, Boolean.TRUE);
        log.trace("JWT jti={} is revoked or absent, cached REVOKED sentinel", jti);
        return null;
    }

    private byte[] resolveSecret(String kid) {
        if (config.getTestSecret() != null && !config.getTestSecret().isEmpty()) {
            return config.getTestSecret().getBytes(StandardCharsets.UTF_8);
        }

        String cached = secretCache.getIfPresent(kid);
        if (cached != null) {
            return cached.getBytes(StandardCharsets.UTF_8);
        }

        if (secretsManagerClient == null) {
            log.error("SecretsManagerClient not available and jwt.test-secret not set");
            return null;
        }

        try {
            String secretValue = secretsManagerClient.getSecretValue(
                    req -> req.secretId(config.getSecretsManagerSecretName()).versionId(kid)
            ).secretString();
            secretCache.put(kid, secretValue);
            return secretValue.getBytes(StandardCharsets.UTF_8);
        } catch (ResourceNotFoundException e) {
            log.warn("Secrets Manager: no version found for kid={}", kid);
            return null;
        } catch (Exception e) {
            log.error("Secrets Manager lookup failed for kid={}: {}", kid, e.getMessage());
            return null;
        }
    }

    public void evictCredential(String jti) {
        credentialCache.invalidate(jti);
        log.info("Evicted credential cache entry for jti={}", jti);
    }
}
