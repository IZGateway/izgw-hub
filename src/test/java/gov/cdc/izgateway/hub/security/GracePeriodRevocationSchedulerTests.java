package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GracePeriodRevocationScheduler}. The repository is mocked; the scheduler
 * delegates the atomic "terminate only if still grace_period" decision to
 * {@link ApiKeyCredentialRepository#terminateIfGracePeriod}, and only audits/evicts when that write
 * wins, emitting {@code apiKeyExpired} or {@code apiKeyRevoked} depending on the resolved terminal
 * status (IGDD-3167: {@code expiresAt <= graceExpiresAt} → expired, else revoked).
 */
@ExtendWith(MockitoExtension.class)
class GracePeriodRevocationSchedulerTests {

    private static final String JTI = "00000000-0000-0000-0000-000000000099";
    private static final String NEW_JTI = "00000000-0000-0000-0000-0000000000aa";
    private static final String JURISDICTION = "MA";
    private static final Instant GRACE_EXPIRES_AT = Instant.parse("2026-07-20T00:00:00Z");

    @Mock private ApiKeyCredentialRepository credentialRepository;
    @Mock private ApiKeyAuditLogger auditLogger;
    @Mock private ApiKeyPrincipalProvider apiKeyPrincipalProvider;

    private GracePeriodRevocationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GracePeriodRevocationScheduler(credentialRepository, auditLogger, apiKeyPrincipalProvider);
    }

    /** A grace-period candidate cut off before its own expiry (expiresAt after graceExpiresAt) → revoked. */
    private ApiKeyCredential revokedCandidate(String jti, String supersededBy) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setJti(jti);
        c.setJurisdictionId(JURISDICTION);
        c.setSupersededBy(supersededBy);
        c.setGraceExpiresAt(GRACE_EXPIRES_AT);
        c.setExpiresAt(GRACE_EXPIRES_AT.plusSeconds(3600));
        return c;
    }

    /** A grace-period candidate that reached its own expiry on/before graceExpiresAt → expired. */
    private ApiKeyCredential expiredCandidate(String jti, String supersededBy) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setJti(jti);
        c.setJurisdictionId(JURISDICTION);
        c.setSupersededBy(supersededBy);
        c.setGraceExpiresAt(GRACE_EXPIRES_AT);
        c.setExpiresAt(GRACE_EXPIRES_AT.minusSeconds(3600));
        return c;
    }

    @Test
    void wonConditionalWrite_revokedOutcome_isAuditedAndEvicted() {
        ApiKeyCredential graceKey = revokedCandidate(JTI, NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates()).thenReturn(List.of(graceKey));
        when(credentialRepository.terminateIfGracePeriod(eq(graceKey), any(Instant.class),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION))).thenReturn(true);

        GracePeriodRevocationScheduler.CycleResult result = scheduler.runRevocationCycle();

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.revoked()).isEqualTo(1);
        assertThat(result.expired()).isZero();
        // Audit event carries the superseding jti; local cache evicted.
        verify(auditLogger).apiKeyRevoked(eq(JTI), eq(JURISDICTION),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION), eq(NEW_JTI));
        verify(auditLogger, never()).apiKeyExpired(any(), any(), any(), any());
        verify(apiKeyPrincipalProvider).evictCredential(JTI);
        // Termination goes through the conditional write only — never the plain store() put.
        verify(credentialRepository, never()).store(any());
    }

    @Test
    void wonConditionalWrite_expiredOutcome_isAuditedAndEvicted() {
        ApiKeyCredential graceKey = expiredCandidate(JTI, NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates()).thenReturn(List.of(graceKey));
        when(credentialRepository.terminateIfGracePeriod(eq(graceKey), any(Instant.class),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_EXPIRATION))).thenReturn(true);

        GracePeriodRevocationScheduler.CycleResult result = scheduler.runRevocationCycle();

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.revoked()).isZero();
        verify(auditLogger).apiKeyExpired(eq(JTI), eq(JURISDICTION),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_EXPIRATION), eq(NEW_JTI));
        verify(auditLogger, never()).apiKeyRevoked(any(), any(), any(), any());
        verify(apiKeyPrincipalProvider).evictCredential(JTI);
        verify(credentialRepository, never()).store(any());
    }

    @Test
    void lostConditionalWrite_noAuditNoEvict() {
        // Another instance already terminated it: the conditional write fails → no audit, no eviction.
        ApiKeyCredential graceKey = revokedCandidate(JTI, NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates()).thenReturn(List.of(graceKey));
        when(credentialRepository.terminateIfGracePeriod(any(), any(), any())).thenReturn(false);

        GracePeriodRevocationScheduler.CycleResult result = scheduler.runRevocationCycle();

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.revoked()).isZero();
        assertThat(result.expired()).isZero();
        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
        verify(credentialRepository, never()).store(any());
    }

    @Test
    void noCandidates_performsNoRevocations() {
        when(credentialRepository.findGraceRevocationCandidates()).thenReturn(List.of());

        GracePeriodRevocationScheduler.CycleResult result = scheduler.runRevocationCycle();

        assertThat(result.evaluated()).isZero();
        assertThat(result.revoked()).isZero();
        assertThat(result.expired()).isZero();
        verify(credentialRepository, never()).terminateIfGracePeriod(any(), any(), any());
        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
    }

    @Test
    void multipleCandidates_auditsOnlyTheOnesWonWithCorrectOutcome() {
        ApiKeyCredential wonRevoked = revokedCandidate("jti-won-revoked", "new-1");
        ApiKeyCredential lost = revokedCandidate("jti-lost", "new-x");
        ApiKeyCredential wonExpired = expiredCandidate("jti-won-expired", "new-2");
        when(credentialRepository.findGraceRevocationCandidates())
                .thenReturn(List.of(wonRevoked, lost, wonExpired));
        when(credentialRepository.terminateIfGracePeriod(eq(wonRevoked), any(), any())).thenReturn(true);
        when(credentialRepository.terminateIfGracePeriod(eq(lost), any(), any())).thenReturn(false);
        when(credentialRepository.terminateIfGracePeriod(eq(wonExpired), any(), any())).thenReturn(true);

        GracePeriodRevocationScheduler.CycleResult result = scheduler.runRevocationCycle();

        // 3 evaluated; only the 2 that won the conditional write are counted, split by outcome.
        assertThat(result.evaluated()).isEqualTo(3);
        assertThat(result.revoked()).isEqualTo(1);
        assertThat(result.expired()).isEqualTo(1);
        verify(auditLogger).apiKeyRevoked(eq("jti-won-revoked"), anyString(), anyString(), eq("new-1"));
        verify(auditLogger).apiKeyExpired(eq("jti-won-expired"), anyString(), anyString(), eq("new-2"));
        verify(auditLogger, never()).apiKeyRevoked(eq("jti-lost"), anyString(), anyString(), anyString());
        verify(auditLogger, never()).apiKeyExpired(eq("jti-lost"), anyString(), anyString(), anyString());
        verify(apiKeyPrincipalProvider).evictCredential("jti-won-revoked");
        verify(apiKeyPrincipalProvider).evictCredential("jti-won-expired");
        verify(apiKeyPrincipalProvider, never()).evictCredential("jti-lost");
    }
}
