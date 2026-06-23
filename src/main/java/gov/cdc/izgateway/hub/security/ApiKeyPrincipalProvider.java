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

    private static final Duration NEGATIVE_SECRET_CACHE_TTL = Duration.ofSeconds(60);

    private final Cache<String, String> secretCache;
    private final Cache<String, Boolean> negativeSecretCache;
    private final Cache<String, ApiKeyPrincipal> credentialCache;
    private final Cache<String, Boolean> revokedCache;
    private final Cache<String, Boolean> absentCache;

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

        this.negativeSecretCache = Caffeine.newBuilder()
                .expireAfterWrite(NEGATIVE_SECRET_CACHE_TTL)
                .build();

        this.credentialCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getCredentialCacheTtl())
                .build();

        this.revokedCache = Caffeine.newBuilder()
                .expireAfterWrite(MAX_TOKEN_LIFETIME)
                .build();

        this.absentCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getCredentialCacheTtl())
                .build();
    }

    public IzgPrincipal getPrincipal(HttpServletRequest request) {
        // Step 1: Extract Bearer token — return null if absent (fallback to cert auth)
        String token;
        try {
            token = jwtTokenExtractor.extractToken(request);
        } catch (InvalidJwtTokenException e) {
            return null;
        }

        // Step 2: Parse JWT header only
        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            log.debug("Failed to parse JWT token: {}", e.getMessage());
            return null;
        }

        // Step 3: Pre-check alg and iss before expensive operations
        if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())) {
            log.warn("JWT rejected: unsupported algorithm={}", signedJwt.getHeader().getAlgorithm());
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
            log.warn("JWT rejected: issuer mismatch (expected={}, got={})", config.getIssuer(), unverifiedClaims.getIssuer());
            return null;
        }

        // Step 4: Resolve signing secret by kid
        String kid = signedJwt.getHeader().getKeyID();
        if (kid == null || kid.isBlank()) {
            log.warn("JWT rejected: missing kid header");
            return null;
        }
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
        JWTClaimsSet claims = unverifiedClaims;
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.now())) {
            log.warn("JWT rejected: expired exp={}", expiry);
            return null;
        }

        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            log.warn("JWT rejected: not yet valid nbf={}", notBefore);
            return null;
        }

        String env = (String) claims.getClaim("env");
        if (!SystemUtils.getDestTypeAsString().equals(env)) {
            log.warn("JWT rejected: env mismatch token env={}, hub env={}", env, SystemUtils.getDestTypeAsString());
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

        if (absentCache.getIfPresent(jti) != null) {
            log.debug("JWT rejected: jti={} is in ABSENT cache", jti);
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
            List<String> roles;
            try {
                roles = claims.getStringListClaim("roles");
            } catch (ParseException e) {
                roles = Collections.emptyList();
            }
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

        if (credentialOpt.isEmpty()) {
            absentCache.put(jti, Boolean.TRUE);
            log.warn("JWT rejected: jti={} not found in DynamoDB", jti);
            return null;
        }

        // Credential found but not active — cache for full token lifetime
        revokedCache.put(jti, Boolean.TRUE);
        log.warn("JWT rejected: jti={} has status={}, cached in REVOKED cache", jti, credentialOpt.get().getStatus());
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

        if (negativeSecretCache.getIfPresent(kid) != null) {
            log.debug("Secrets Manager: kid={} in negative cache, skipping lookup", kid);
            return null;
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
            log.warn("Secrets Manager: no version found for kid={}, caching negative result", kid);
            negativeSecretCache.put(kid, Boolean.TRUE);
            return null;
        } catch (Exception e) {
            log.error("Secrets Manager lookup failed for kid={}: {}", kid, e.getMessage());
            return null;
        }
    }

    public void evictCredential(String jti) {
        credentialCache.invalidate(jti);
        absentCache.invalidate(jti);
        revokedCache.put(jti, Boolean.TRUE);
        log.info("Evicted credential cache entries for jti={}", jti);
    }
}
