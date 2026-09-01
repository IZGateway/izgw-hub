package gov.cdc.izgateway.hub.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.principal.InvalidJwtTokenException;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyPrincipalProviderTests {

    private static final String TEST_SECRET = "izg-test-secret-igdd-2705-do-not-use-in-production";
    private static final String TEST_ISSUER = "http://localhost:3000";
    private static final String TEST_JTI = "018f4e2a-5678-7abc-8def-000000000002";
    private static final String TEST_KID = "00000000-0000-0000-0000-000000000001";
    // The environment this Hub instance reports as its target; a credential must list this to authenticate.
    private static final int    TEST_ENV_ID  = SystemUtils.getDestType();

    @Mock private ApiKeyCredentialRepository credentialRepository;
    @Mock private JwtTokenExtractor jwtTokenExtractor;
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
        provider = new ApiKeyPrincipalProvider(config, credentialRepository, jwtTokenExtractor, null);
    }

    private static final String TEST_UPN = "test.example.gov";

    /** An active credential valid for this Hub's target environment. */
    private static ApiKeyCredential activeCredForThisEnv() {
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        cred.setEnvironments(Set.of(TEST_ENV_ID));
        cred.setUseTypes(Set.of("PROVIDER"));
        return cred;
    }

    private String buildToken(String alg, String issuer, String jti, Date exp) throws Exception {
        return buildToken(alg, issuer, jti, exp, TEST_UPN);
    }

    private String buildToken(String alg, String issuer, String jti, Date exp, String upn) throws Exception {
        JWSAlgorithm jwsAlg = "HS256".equals(alg) ? JWSAlgorithm.HS256 : JWSAlgorithm.RS256;
        JWSHeader header = new JWSHeader.Builder(jwsAlg).keyID(TEST_KID).build();
        // No `env` claim: environment authorization is a server-side property of the credential (IGDD-3140).
        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("TEST_ORG")
                .jwtID(jti)
                .expirationTime(exp != null ? exp : Date.from(Instant.now().plus(Duration.ofDays(365))));
        if (upn != null) {
            claimsBuilder.claim("upn", upn);
        }
        JWTClaimsSet claims = claimsBuilder.build();
        if (!"HS256".equals(alg)) {
            return "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJvdGhlciJ9.signature";
        }
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    void happyPath_validJwtActiveCredential_returnsApiKeyPrincipal() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(activeCredForThisEnv()));

        IzgPrincipal principal = provider.getPrincipal(request);

        assertThat(principal).isInstanceOf(ApiKeyPrincipal.class);
        ApiKeyPrincipal apiKey = (ApiKeyPrincipal) principal;
        assertThat(apiKey.getName()).isEqualTo(TEST_UPN);
        assertThat(apiKey.getUpn()).isEqualTo(TEST_UPN);
        assertThat(apiKey.getJti()).isEqualTo(TEST_JTI);
        assertThat(apiKey.getOrganization()).isEqualTo("TEST_ORG");
        assertThat(apiKey.getRoles()).isEmpty();
        // useTypes is read from the credential record (never the token) for the routing-time
        // intersection check against the destination jurisdiction's allowedUseTypes (IGDD-3257).
        assertThat(apiKey.getUseTypes()).containsExactly("PROVIDER");
    }

    @Test
    void wrongAlgorithm_throws_noSmCall() throws Exception {
        when(jwtTokenExtractor.extractToken(request)).thenReturn(
                "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJvdGhlciJ9.sig");

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void wrongIssuer_throws_noSmCall() throws Exception {
        String token = buildToken("HS256", "https://other.example.com", TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void expiredToken_throws() throws Exception {
        Date past = Date.from(Instant.now().minus(Duration.ofHours(1)));
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, past);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
    }

    @Test
    void targetEnvNotInCredentialEnvironments_throws() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        // Active credential, but its `environments` set does NOT contain this Hub's target environment.
        int otherEnv = TEST_ENV_ID == 1 ? 2 : 1;
        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("active");
        cred.setEnvironments(Set.of(otherEnv));
        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(cred));

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
    }

    @Test
    void revokedSentinelInCache_throws_noDynamoDbCall() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        // Pre-populate revoked sentinel
        provider.evictCredential(TEST_JTI);

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        verifyNoInteractions(credentialRepository);
    }

    @Test
    void dynamoDbRevokedStatus_throws_insertsSentinel() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        ApiKeyCredential cred = new ApiKeyCredential();
        cred.setStatus("revoked");
        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(cred));

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);

        // Subsequent request must hit sentinel (no DynamoDB call)
        String token2 = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token2);
        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        verify(credentialRepository, times(1)).findByJti(anyString());
    }

    @Test
    void missingUpnClaim_throws() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null, null);
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(activeCredForThisEnv()));

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
    }

    @Test
    void blankUpnClaim_throws() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null, "");
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);

        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(activeCredForThisEnv()));

        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
    }

    @Test
    void evictCredential_insertsRevokedSentinel_subsequentLookupThrows() throws Exception {
        String token = buildToken("HS256", TEST_ISSUER, TEST_JTI, null);
        when(credentialRepository.findByJti(TEST_JTI)).thenReturn(Optional.of(activeCredForThisEnv()));

        // First call — populates active cache
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        IzgPrincipal first = provider.getPrincipal(request);
        assertThat(first).isInstanceOf(ApiKeyPrincipal.class);

        // Evict
        provider.evictCredential(TEST_JTI);

        // Second call — must throw from REVOKED sentinel
        when(jwtTokenExtractor.extractToken(request)).thenReturn(token);
        assertThatThrownBy(() -> provider.getPrincipal(request))
                .isInstanceOf(ApiKeyAuthenticationException.class);
        verify(credentialRepository, times(1)).findByJti(anyString());
    }

    @Test
    void noApiKeyPresented_returnsNull_eligibleForCertFallback() throws Exception {
        // Absent or non-Bearer Authorization header — JwtTokenExtractor signals this the same way
        // regardless of which; either way, no API key was presented, so the caller can still fall
        // back to certificate authentication.
        when(jwtTokenExtractor.extractToken(request)).thenThrow(new InvalidJwtTokenException("no bearer token"));

        assertThat(provider.getPrincipal(request)).isNull();
        verifyNoInteractions(credentialRepository);
    }
}
