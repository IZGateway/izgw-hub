package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Unit tests for {@link GracePeriodRevocationScheduler}. The repository finder is mocked so these
 * tests exercise the revocation cycle independently of DynamoDB.
 */
@ExtendWith(MockitoExtension.class)
class GracePeriodRevocationSchedulerTests {

    private static final String JTI = "018f4e2a-5678-7abc-8def-000000000099";
    private static final String NEW_JTI = "018f4e2a-5678-7abc-8def-0000000000aa";
    private static final String JURISDICTION = "MA";

    @Mock private ApiKeyCredentialRepository credentialRepository;
    @Mock private ApiKeyAuditLogger auditLogger;
    @Mock private ApiKeyPrincipalProvider apiKeyPrincipalProvider;

    private GracePeriodRevocationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GracePeriodRevocationScheduler(credentialRepository, auditLogger, apiKeyPrincipalProvider);
    }

    private ApiKeyCredential credential(String jti, String status, String supersededBy) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setJti(jti);
        c.setStatus(status);
        c.setJurisdictionId(JURISDICTION);
        c.setSupersededBy(supersededBy);
        return c;
    }

    @Test
    void activeCandidate_isRevokedAuditedAndEvicted() {
        ApiKeyCredential active = credential(JTI, "active", NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of(active));

        scheduler.runRevocationCycle();

        // Status transition persisted with system actor and a revocation timestamp.
        assertThat(active.getStatus()).isEqualTo("revoked");
        assertThat(active.getRevokedBy()).isEqualTo(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION);
        assertThat(active.getRevokedAt()).isNotNull();
        verify(credentialRepository).store(active);

        // Audit event carries the superseding jti.
        verify(auditLogger).apiKeyRevoked(eq(JTI), eq(JURISDICTION), eq(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION), eq(NEW_JTI));

        // Local cache eviction performed.
        verify(apiKeyPrincipalProvider).evictCredential(JTI);
    }

    @Test
    void nonActiveCandidate_isSkipped() {
        ApiKeyCredential alreadyRevoked = credential(JTI, "revoked", NEW_JTI);
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of(alreadyRevoked));

        scheduler.runRevocationCycle();

        verify(credentialRepository, never()).store(any());
        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
    }

    @Test
    void noCandidates_performsNoRevocations() {
        when(credentialRepository.findGraceRevocationCandidates(anyString())).thenReturn(List.of());

        scheduler.runRevocationCycle();

        verify(credentialRepository, never()).store(any());
        verifyNoInteractions(auditLogger, apiKeyPrincipalProvider);
    }

    @Test
    void multipleCandidates_revokesOnlyActiveOnes() {
        ApiKeyCredential active1 = credential("jti-active-1", "active", "new-1");
        ApiKeyCredential revokedAlready = credential("jti-revoked", "revoked", "new-x");
        ApiKeyCredential active2 = credential("jti-active-2", "active", "new-2");
        when(credentialRepository.findGraceRevocationCandidates(anyString()))
                .thenReturn(List.of(active1, revokedAlready, active2));

        scheduler.runRevocationCycle();

        verify(credentialRepository).store(active1);
        verify(credentialRepository).store(active2);
        verify(credentialRepository, never()).store(revokedAlready);
        verify(auditLogger).apiKeyRevoked(eq("jti-active-1"), anyString(), anyString(), eq("new-1"));
        verify(auditLogger).apiKeyRevoked(eq("jti-active-2"), anyString(), anyString(), eq("new-2"));
        verify(apiKeyPrincipalProvider).evictCredential("jti-active-1");
        verify(apiKeyPrincipalProvider).evictCredential("jti-active-2");
    }
}
