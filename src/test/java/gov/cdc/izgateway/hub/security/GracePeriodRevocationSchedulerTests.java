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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GracePeriodRevocationScheduler}. The repository is mocked; the scheduler
 * delegates the atomic "revoke only if still grace_period" decision to
 * {@link ApiKeyCredentialRepository#revokeIfGracePeriod}, and only audits/evicts when that write wins.
 */
@ExtendWith(MockitoExtension.class)
class GracePeriodRevocationSchedulerTests {

    private static final String JTI = "00000000-0000-0000-0000-000000000099";
    private static final String NEW_JTI = "00000000-0000-0000-0000-0000000000aa";
    private static final String JURISDICTION = "MA";

    @Mock private ApiKeyCredentialRepository credentialRepository;
    @Mock private ApiKeyAuditLogger auditLogger;
    @Mock private ApiKeyPrincipalProvider apiKeyPrincipalProvider;

    private GracePeriodRevocationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GracePeriodRevocationScheduler(credentialRepository, auditLogger, apiKeyPrincipalProvider);
    }

    private ApiKeyCredential credential(String jti, String supersededBy) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setJti(jti);
        c.setJurisdictionId(JURISDICTION);
        c.setSupersededBy(supersededBy);
        return c;
    }

    @Test
    void wonConditionalWrite_isAuditedAndEvicted() {
        ApiKeyCredential graceKey = credential(JTI, NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of(graceKey));
        when(credentialRepository.revokeIfGracePeriod(eq(graceKey), any(Instant.class),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION))).thenReturn(true);

        scheduler.runRevocationCycle();

        // Audit event carries the superseding jti; local cache evicted.
        verify(auditLogger).apiKeyRevoked(eq(JTI), eq(JURISDICTION),
                eq(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION), eq(NEW_JTI));
        verify(apiKeyPrincipalProvider).evictCredential(JTI);
    }

    @Test
    void lostConditionalWrite_noAuditNoEvict() {
        // Another instance already revoked it: the conditional write fails → no audit, no eviction.
        ApiKeyCredential graceKey = credential(JTI, NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of(graceKey));
        when(credentialRepository.revokeIfGracePeriod(any(), any(), any())).thenReturn(false);

        scheduler.runRevocationCycle();

        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
    }

    @Test
    void noCandidates_performsNoRevocations() {
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of());

        scheduler.runRevocationCycle();

        verify(credentialRepository, never()).revokeIfGracePeriod(any(), any(), any());
        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
    }

    @Test
    void multipleCandidates_auditsOnlyTheOnesWon() {
        ApiKeyCredential won1 = credential("jti-won-1", "new-1");
        ApiKeyCredential lost = credential("jti-lost", "new-x");
        ApiKeyCredential won2 = credential("jti-won-2", "new-2");
        when(credentialRepository.findGraceRevocationCandidates(anyString()))
                .thenReturn(List.of(won1, lost, won2));
        when(credentialRepository.revokeIfGracePeriod(eq(won1), any(), any())).thenReturn(true);
        when(credentialRepository.revokeIfGracePeriod(eq(lost), any(), any())).thenReturn(false);
        when(credentialRepository.revokeIfGracePeriod(eq(won2), any(), any())).thenReturn(true);

        scheduler.runRevocationCycle();

        verify(auditLogger).apiKeyRevoked(eq("jti-won-1"), anyString(), anyString(), eq("new-1"));
        verify(auditLogger).apiKeyRevoked(eq("jti-won-2"), anyString(), anyString(), eq("new-2"));
        verify(auditLogger, never()).apiKeyRevoked(eq("jti-lost"), anyString(), anyString(), anyString());
        verify(apiKeyPrincipalProvider).evictCredential("jti-won-1");
        verify(apiKeyPrincipalProvider).evictCredential("jti-won-2");
        verify(apiKeyPrincipalProvider, never()).evictCredential("jti-lost");
    }
}
