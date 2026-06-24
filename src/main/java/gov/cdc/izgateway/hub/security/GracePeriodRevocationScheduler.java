package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that revokes superseded API-key credentials after their grace period expires
 * (IGDD-2711, User Story 10).
 *
 * <p>When Config Console renews an API key (IGDD-2707) it issues a new credential and stamps the
 * old one with {@code supersededBy} and {@code graceExpiresAt}, leaving it {@code active} so both
 * keys authenticate during the grace window. Once {@code graceExpiresAt} passes, the old key must
 * be revoked so renewed keys do not accumulate as indefinitely-valid credentials. This job performs
 * that sweep inside Hub, reusing the credential repository, audit logger, and the existing
 * cross-instance cache-eviction path built for IGDD-2705.</p>
 *
 * <p>The job is gated on {@code apikey.grace-revocation.enabled} (off by default) so it can be
 * enabled per environment and stays inert until the DynamoDB grace-field contract with Config
 * Console is confirmed. {@link EnableScheduling} is declared here so Spring's scheduling
 * infrastructure is only activated when this bean is present.</p>
 *
 * <p><b>Skeleton status:</b> the revocation cycle is wired end-to-end, but two pieces are stubbed
 * pending dependencies and are marked with {@code TODO}: (1) candidate selection in
 * {@link ApiKeyCredentialRepository#findGraceRevocationCandidates(String)} (needs the
 * {@code graceExpiresAt} field from IGDD-2707; currently returns empty, so the cycle is a no-op);
 * (2) the multi-instance single-runner guard ({@link #isDesignatedRunner()}); and (3) cross-instance
 * eviction broadcast (currently evicts locally only).</p>
 */
@Component
@Slf4j
@EnableScheduling
@ConditionalOnProperty(prefix = "apikey.grace-revocation", name = "enabled", havingValue = "true")
public class GracePeriodRevocationScheduler {

    private final ApiKeyCredentialRepository credentialRepository;
    private final ApiKeyAuditLogger auditLogger;
    private final ApiKeyPrincipalProvider apiKeyPrincipalProvider;

    @Autowired
    public GracePeriodRevocationScheduler(
            ApiKeyCredentialRepository credentialRepository,
            ApiKeyAuditLogger auditLogger,
            ApiKeyPrincipalProvider apiKeyPrincipalProvider
    ) {
        this.credentialRepository = credentialRepository;
        this.auditLogger = auditLogger;
        this.apiKeyPrincipalProvider = apiKeyPrincipalProvider;
    }

    /**
     * Scheduled entry point. Runs at a fixed delay after the previous completion. Wraps the cycle
     * so that any failure is logged (for CloudWatch alerting per AC #3) and never escapes to the
     * scheduler thread.
     */
    @Scheduled(
            fixedDelayString = "${apikey.grace-revocation.interval:PT1H}",
            initialDelayString = "${apikey.grace-revocation.initial-delay:PT5M}"
    )
    public void scheduledRun() {
        String previousEventId = MDC.get(EventId.EVENTID_KEY);
        MDC.put(EventId.EVENTID_KEY, EventId.DEFAULT_TX_ID);
        try {
            runRevocationCycle();
        } catch (Exception e) {  // NOSONAR — a scheduled job must never let a failure escape its thread
            // Logged at ERROR so a CloudWatch log-based alarm can detect job failure (AC #3).
            log.error(Markers2.append(e), "Grace-period revocation cycle failed: {}", e.getMessage());
        } finally {
            if (previousEventId != null) {
                MDC.put(EventId.EVENTID_KEY, previousEventId);
            } else {
                MDC.remove(EventId.EVENTID_KEY);
            }
        }
    }

    /**
     * Execute one revocation cycle: find superseded credentials whose grace period has expired in
     * this environment, revoke each, emit an audit event, and propagate cache eviction. Logs the
     * number of credentials evaluated and revoked for operational visibility (AC #4).
     */
    void runRevocationCycle() {
        if (!isDesignatedRunner()) {
            log.debug("Skipping grace-period revocation cycle: this instance is not the designated runner");
            return;
        }

        String env = SystemUtils.getDestTypeAsString();
        List<ApiKeyCredential> candidates = credentialRepository.findGraceRevocationCandidates(env);

        int evaluated = candidates.size();
        int revoked = 0;
        for (ApiKeyCredential credential : candidates) {
            // Idempotency guard: never re-revoke a credential that is no longer active.
            if (!"active".equals(credential.getStatus())) {
                continue;
            }
            revokeCredential(credential);
            revoked++;
        }

        log.info(Markers2.append(
                "eventType", "GRACE_REVOCATION_RUN",
                "environment", env,
                "evaluated", evaluated,
                "revoked", revoked
        ), "Grace-period revocation cycle complete: evaluated={}, revoked={}", evaluated, revoked);
    }

    /**
     * Revoke a single superseded credential: transition it to {@code revoked} in DynamoDB, emit the
     * {@code API_KEY_REVOKED} audit event, and propagate cache eviction so no instance can
     * re-validate it from a warm cache.
     *
     * @param credential the active, grace-expired credential to revoke
     */
    private void revokeCredential(ApiKeyCredential credential) {
        String jti = credential.getJti();

        credential.setStatus("revoked");
        credential.setRevokedAt(Instant.now());
        credential.setRevokedBy(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION);
        credentialRepository.store(credential);

        // TODO(IGDD-2711, task 1.1): pass credential.getSupersededBy() once that field is added to
        // ApiKeyCredential (written by Config Console on renewal, IGDD-2707).
        auditLogger.apiKeyRevoked(
                jti,
                credential.getJurisdictionId(),
                ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION,
                null
        );

        propagateEviction(jti);
    }

    /**
     * Propagate revocation to every Hub instance's credential cache.
     *
     * <p><b>Skeleton:</b> currently evicts only the local instance. The full implementation must
     * broadcast a {@code RefreshRequest} carrying the {@code jti} through {@code RefreshQueueService}
     * (the same SQS path Config Console's manual revoke triggers via {@code /rest/refresh}) so every
     * instance evicts the credential. {@code RefreshQueueService} is currently constructed privately
     * inside {@code DbController}, and {@code DbController.getRefreshed(...)} is guarded by
     * {@code @RolesAllowed}, so a non-HTTP broadcast entry point is needed before this can be wired
     * from a scheduler thread.</p>
     *
     * @param jti the revoked credential identifier to evict
     */
    private void propagateEviction(String jti) {
        // TODO(IGDD-2711, task 3.6): broadcast jti eviction to all instances via RefreshQueueService.
        apiKeyPrincipalProvider.evictCredential(jti);
    }

    /**
     * Determine whether this instance should perform the revocation cycle, so that in a multi-instance
     * deployment a single instance acts per cycle (avoiding duplicate writes and audit/eviction noise).
     *
     * <p><b>Skeleton:</b> always returns {@code true}. The implementation should reuse the
     * host-ordering approach in {@code StatusCheckScheduler} or a conditional DynamoDB write-lock
     * (change task 3.3, design D5). Revocation is idempotent, so a missing guard affects noise, not
     * correctness.</p>
     *
     * @return {@code true} if this instance should run the cycle
     */
    private boolean isDesignatedRunner() {
        // TODO(IGDD-2711, task 3.3): implement single-runner guard for multi-instance deployments.
        return true;
    }
}
