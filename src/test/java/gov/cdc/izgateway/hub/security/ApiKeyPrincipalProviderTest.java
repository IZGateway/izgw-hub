package gov.cdc.izgateway.hub.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.principal.JwtTokenExtractor;
import gov.cdc.izgateway.utils.SystemUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyPrincipalProviderTest {

    private static final String TEST_SECRET = "izg-test-secret-igdd-2705-do-not-use-in-production";
    private static final String TEST_ISSUER = "http://localhost:3000";
    private static final String TEST_JTI = "018f4e2a-5678-7abc-8def-000000000002";
    private static final String TEST_KID = "00000000-0000-0000-0000-000000000001";
    private static final String TEST_ENV = SystemUtils.getDestTypeAsString();

    @Mock private ApiKeyCredentialRepository credentialRepository;
    @Mock private JwtTokenExtractor jwtTokenExtractor;
    @Mock private ApiKeyAuditLogger auditLogger;
    @Mock private HttpServletRequest request;

    private JwtConfig config;
    private ApiKeyPrincipalProvider provider;

    @BeforeEach
    void setUp() {
        config = new JwtConfig();
        config.setIssuer(TEST_ISSUER);
        config.setTestSecret(TEST_SECRET);
        config.setSecretCacheTtl(Duration.ofHours(1));
        config.setCredentialCacheTtl(Duration.ofMinutes(5));
        provider = new ApiKeyPrincipalProvider(config, credentialRepository, jwtTokenExtractor, auditLogger, null);
    }

    private String buildToken(String alg, String issuer, String jti, String env, Date exp) throws Exception {
        JWSAlgorithm jwsAlg = "HS256".equals(alg) ? JWSAlgorithm.HS256 : JWSAlgorithm.RS256;
        JWSHeader header = new JWSHeader.Builder(jwsAlg).keyID(TEST_KID).build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("TEST_ORG")
                .jwtID(jti)
                .expirationTime(exp != null ? exp : Date.from(Instant.now().plus(Duration.ofDays(365))))
                .claim("env", env)
                .claim("dns", "test.example.gov")
                .claim("roles", List.of("ads", "soap"))
                .build();
        if (!"HS256".equals(alg)) {
            return "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJvdGhlciJ9.signature";
        }
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void happyPath_validJwtActiveCredential_returnsApiKeyPrincipal() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isInstanceOf(ApiKeyPrincipal.class);
        ApiKeyPrincipal apiKey = (ApiKeyPrincipal) principal;
        assertThat(apiKey.getJti()).isEqualTo(TEST_JTI);
        assertThat(apiKey.getOrganization()).isEqualTo("TEST_ORG");
        assertThat(apiKey.getDns()).isEqualTo("test.example.gov");
        assertThat(apiKey.getRoles()).contains("ads", "soap");
    }

    @Test
    void wrongAlgorithm_returnsNull_noSmCall() throws Exception {
        when(jwtTokenExtractor.extractToken(request)).thenReturn(
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJvdGhlciJ9.sig");

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isNull();
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void wrongIssuer_returnsNull_noSmCall() throws Exception {
        String token = buildToken("HS256", "https://other.example.com", TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isNull();
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void expiredToken_returnsNull() throws Exception {
        Date past = Date.from(Instant.now().minus(Duration.ofHours(1)));
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, past);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isNull();
    }

    @Test
    void wrongEnv_returnsNull() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, "Production", null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        IzgPrincipal principal = provider.getProvider(request);

        // Only fails if current env != Production; in dev/test environments this will be null
        // because TEST_ENV != "Production"
        if (!"Production".equals(TEST_ENV)) {
            assertThat(principal).isNull();
        }
    }

    @Test
    void evictCredential_clearsActiveCache_forcesRevalidation() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        // First call caches the active principal (1 DynamoDB lookup)
        assertThat(provider.getProvider(request)).isInstanceOf(ApiKeyPrincipal.class);

        // evictCredential invalidates both caches (it does not insert a revoked sentinel)
        provider.evictCredential(TEST_JTI);

        // Second call finds an empty cache and must re-validate against DynamoDB
        assertThat(provider.getProvider(request)).isInstanceOf(ApiKeyPrincipal.class);
        verify(credentialRepository, times(2)).findByEnvAndJti(anyString(), anyString());
    }

    @Test
    void dynamoDbRevokedStatus_returnsNull_insertsSentinel() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("revoked");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isNull();

        // Subsequent request must hit sentinel (no DynamoDB call)
        String token2 = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token2);
        IzgPrincipal principal2 = provider.getProvider(request);
        assertThat(principal2).isNull();
        verify(credentialRepository, times(1)).findByEnvAndJti(anyString(), anyString());
    }

    // ---- IGDD-2704: audit event emission ----

    @Test
    void happyPath_emitsApiKeyUsedAuditEvent() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));

        provider.getProvider(request);

        verify(auditLogger).apiKeyUsed(TEST_JTI, "TEST_ORG", "203.0.113.7");
        verify(auditLogger, never()).apiKeyAuthFailed(anyString(), anyString(), anyString());
    }

    @Test
    void cachedActiveCredential_emitsApiKeyUsedOnEveryUse() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        provider.getProvider(request); // DynamoDB miss path
        provider.getProvider(request); // credential cache hit path

        // API_KEY_USED is emitted on both the cache-miss and cache-hit successes.
        verify(auditLogger, times(2)).apiKeyUsed(TEST_JTI, "TEST_ORG", "203.0.113.7");
    }

    @Test
    void noBearerToken_emitsNoAuditEvent() throws Exception {
        when(jwtTokenExtractor.extractToken(request))
                .thenThrow(new gov.cdc.izgateway.security.principal.InvalidJwtTokenException("no token"));

        IzgPrincipal principal = provider.getProvider(request);

        assertThat(principal).isNull();
        verifyNoInteractions(auditLogger);
    }

    @Test
    void wrongIssuer_emitsNoAuditEvent_silentCertFallback() throws Exception {
        String token = buildToken("HS256", "https://other.example.com", TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        provider.getProvider(request);

        // Not an izg API key — must fall through silently without an audit failure event.
        verifyNoInteractions(auditLogger);
    }

    @Test
    void expiredToken_emitsApiKeyAuthFailed() throws Exception {
        Date past = Date.from(Instant.now().minus(Duration.ofHours(1)));
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, past);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        provider.getProvider(request);

        verify(auditLogger).apiKeyAuthFailed(TEST_JTI, "203.0.113.7", "token expired");
        verify(auditLogger, never()).apiKeyUsed(anyString(), anyString(), anyString());
    }

    @Test
    void dynamoDbRevokedStatus_emitsApiKeyAuthFailed() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, TEST_ENV, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("revoked");
        when(credentialRepository.findByEnvAndJti(TEST_ENV, TEST_JTI)).thenReturn(Optional.of(cred));

        // First call: DynamoDB reports revoked -> emits "credential status revoked" and caches in revokedCache.
        provider.getProvider(request);
        // Second call: served from revokedCache -> emits "credential revoked" with no further DynamoDB lookup.
        provider.getProvider(request);

        verify(auditLogger).apiKeyAuthFailed(TEST_JTI, "203.0.113.7", "credential status revoked");
        verify(auditLogger).apiKeyAuthFailed(TEST_JTI, "203.0.113.7", "credential revoked");
        verify(credentialRepository, times(1)).findByEnvAndJti(anyString(), anyString());
    }
}
