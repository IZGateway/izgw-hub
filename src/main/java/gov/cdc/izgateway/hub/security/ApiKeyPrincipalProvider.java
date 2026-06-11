package gov.cdc.izgateway.hub.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
    private final Cache<String, ApiKeyPrincipal> credentialCache;
    private final Cache<String, Boolean> revokedCache;

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
                .expireAfterWrite(config.getCredentialCacheTtl())
                .build();

        this.revokedCache = Caffeine.newBuilder()
                .expireAfterWrite(MAX_TOKEN_LIFETIME)
                .build();
    }

    public IzgPrincipal getProvider(HttpServletRequest request) {
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
            log.warn("JWT rejected: failed to parse claims: {}", e.getMessage());
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
            log.warn("JWT rejected: expired exp={}", expiry);
            return null;
        }

        String env = (String) claims.getClaim("env");
        if (!SystemUtils.getDestTypeAsString().equals(env)) {
            log.warn("JWT rejected: env mismatch token env={}, hub env={}", env, SystemUtils.getDestTypeAsString());
            return null;
        }

        if (!config.getIssuer().equals(claims.getIssuer())) {
            log.warn("JWT rejected: issuer mismatch in verified payload (expected={}, got={})", config.getIssuer(), claims.getIssuer());
            return null;
        }

        // Step 7: Credential cache lookup by jti
        String jti = claims.getJWTID();
        if (jti == null) {
            log.warn("JWT rejected: missing jti claim");
            return null;
        }

        if (revokedCache.getIfPresent(jti) != null) {
            log.warn("JWT rejected: jti={} is in REVOKED cache", jti);
            return null;
        }

        ApiKeyPrincipal cached = credentialCache.getIfPresent(jti);
        if (cached != null) {
            return cached;
        }

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

        // Revoked, expired, or absent — add to revoked cache with max-token-lifetime TTL
        revokedCache.put(jti, Boolean.TRUE);
        log.warn("JWT rejected: jti={} is revoked or absent, cached in REVOKED cache", jti);
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
        revokedCache.invalidate(jti);
        log.info("Evicted credential cache entries for jti={}", jti);
    }
}
