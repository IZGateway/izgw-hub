package gov.cdc.izgateway.hub.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
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
import java.util.Date;

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
        // Step 1: Extract Bearer token — return null if absent (fallback to cert auth is still allowed;
        // no API key was presented at all).
        String token;
        try {
            token = jwtTokenExtractor.extractToken(request);
        } catch (InvalidJwtTokenException e) {
            return null;
        }

        // From here on, a Bearer-scheme Authorization header WAS presented. Every failure below throws
        // ApiKeyAuthenticationException instead of returning null: a caller that presents an API key and
        // fails authentication must not be silently retried against the client certificate.

        // Step 2: Parse JWT header only
        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            log.warn("JWT rejected: failed to parse token: {}", e.getMessage());
            throw new ApiKeyAuthenticationException("Failed to parse JWT token: " + e.getMessage());
        }

        // Step 3: Pre-check alg and iss before expensive operations
        if (!JWSAlgorithm.HS256.equals(signedJwt.getHeader().getAlgorithm())) {
            log.warn("JWT rejected: unsupported algorithm={}", signedJwt.getHeader().getAlgorithm());
            throw new ApiKeyAuthenticationException("Unsupported algorithm=" + signedJwt.getHeader().getAlgorithm());
        }

        JWTClaimsSet unverifiedClaims;
        try {
            unverifiedClaims = signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            log.warn("JWT rejected: failed to parse claims: {}", e.getMessage());
            throw new ApiKeyAuthenticationException("Failed to parse JWT claims: " + e.getMessage());
        }

        if (!config.getIssuer().equals(unverifiedClaims.getIssuer())) {
            log.warn("JWT rejected: issuer mismatch (expected={}, got={})", config.getIssuer(), unverifiedClaims.getIssuer());
            throw new ApiKeyAuthenticationException("Issuer mismatch (expected=" + config.getIssuer() + ", got=" + unverifiedClaims.getIssuer() + ")");
        }

        // Step 4: Resolve signing secret by kid
        String kid = signedJwt.getHeader().getKeyID();
        if (kid == null || kid.isBlank()) {
            log.warn("JWT rejected: missing kid header");
            throw new ApiKeyAuthenticationException("Missing kid header");
        }
        byte[] secretBytes = resolveSecret(kid);
        if (secretBytes == null) {
            log.warn("JWT rejected: unable to resolve signing secret for kid={}", kid);
            throw new ApiKeyAuthenticationException("Unable to resolve signing secret for kid=" + kid);
        }

        // Step 5: Verify HS256 signature
        try {
            if (!signedJwt.verify(new MACVerifier(secretBytes))) {
                log.warn("JWT signature verification failed for kid={}", kid);
                throw new ApiKeyAuthenticationException("Signature verification failed for kid=" + kid);
            }
        } catch (JOSEException e) {
            log.warn("JWT verification exception for kid={}: {}", kid, e.getMessage());
            throw new ApiKeyAuthenticationException("Verification exception for kid=" + kid + ": " + e.getMessage());
        }

        // Step 6: Validate claims against verified payload
        JWTClaimsSet claims = unverifiedClaims;
        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isBefore(Instant.now())) {
            log.warn("JWT rejected: expired exp={}", expiry);
            throw new ApiKeyAuthenticationException("Expired exp=" + expiry);
        }

        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            log.warn("JWT rejected: not yet valid nbf={}", notBefore);
            throw new ApiKeyAuthenticationException("Not yet valid nbf=" + notBefore);
        }

        // Step 7: Credential cache lookup by jti
        // Note: the token carries no `env` claim — environment authorization is a server-side property
        // of the credential and is enforced against its `environments` list after the DynamoDB lookup
        // (see lookupAndCacheCredential).
        String jti = claims.getJWTID();
        if (jti == null) {
            log.warn("JWT rejected: missing jti claim");
            throw new ApiKeyAuthenticationException("Missing jti claim");
        }

        if (revokedCache.getIfPresent(jti) != null) {
            log.warn("JWT rejected: jti={} is in REVOKED cache", jti);
            throw new ApiKeyAuthenticationException("jti=" + jti + " is in REVOKED cache");
        }

        if (absentCache.getIfPresent(jti) != null) {
            log.debug("JWT rejected: jti={} is in ABSENT cache", jti);
            throw new ApiKeyAuthenticationException("jti=" + jti + " is in ABSENT cache");
        }

        ApiKeyPrincipal cached = credentialCache.getIfPresent(jti);
        if (cached != null) {
            return cached;
        }

        // Step 8: DynamoDB lookup on cache miss
        return lookupAndCacheCredential(claims, jti);
    }

    private IzgPrincipal lookupAndCacheCredential(JWTClaimsSet claims, String jti) {
        var credentialOpt = credentialRepository.findByJti(jti);

        if (credentialOpt.isPresent() && isUsableStatus(credentialOpt.get().getStatus())) {
            ApiKeyCredential credential = credentialOpt.get();

            // Environment authorization: the request's target environment must be in the credential's
            // server-side `environments` set (a DynamoDB Number Set). A mismatch is cached in the short-lived
            // absentCache (not the 366-day revoked sentinel) so an `environments` edit takes effect within the
            // credential TTL.
            //
            // DynamoDB cannot store an empty set, so an absent attribute arrives here as null; null and empty
            // both mean "valid in no environment" and are handled by the same deny below.
            Integer targetEnv = SystemUtils.getDestType();
            if (credential.getEnvironments() == null || !credential.getEnvironments().contains(targetEnv)) {
                absentCache.put(jti, Boolean.TRUE);
                log.warn("JWT rejected: jti={} not valid for target env={} (environments={})",
                        jti, targetEnv, credential.getEnvironments());
                throw new ApiKeyAuthenticationException("jti=" + jti + " not valid for target env=" + targetEnv);
            }

            String sub = claims.getSubject();
            String upn = (String) claims.getClaim("upn");
            if (upn == null || upn.isBlank()) {
                log.warn("JWT rejected: missing or blank upn claim for jti={}", jti);
                throw new ApiKeyAuthenticationException("Missing or blank upn claim for jti=" + jti);
            }

            // useTypes is carried on the principal so the routing-time intersection check (IGDD-3257) uses the
            // credential looked up by jti without a second DynamoDB read per message; it refreshes on the
            // credential cache TTL, the same window that governs an `environments` edit.
            ApiKeyPrincipal principal = new ApiKeyPrincipal(
                    sub,
                    jti,
                    upn,
                    config.getIssuer(),
                    credential.getUseTypes()
            );
            credentialCache.put(jti, principal);
            log.debug("Authenticated ApiKeyPrincipal jti={} org={} upn={}", jti, sub, upn);
            return principal;
        }

        if (credentialOpt.isEmpty()) {
            absentCache.put(jti, Boolean.TRUE);
            log.warn("JWT rejected: jti={} not found in DynamoDB", jti);
            throw new ApiKeyAuthenticationException("jti=" + jti + " not found in DynamoDB");
        }

        // Credential found but not in a usable state (e.g. validated, expired, revoked) — cache for full token lifetime
        revokedCache.put(jti, Boolean.TRUE);
        log.warn("JWT rejected: jti={} has non-usable status={}, cached in REVOKED cache", jti, credentialOpt.get().getStatus());
        throw new ApiKeyAuthenticationException("jti=" + jti + " has non-usable status=" + credentialOpt.get().getStatus());
    }

    private static boolean isUsableStatus(String status) {
        return "active".equals(status) || "grace_period".equals(status);
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
