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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that revokes superseded API-key credentials after their grace period expires
 * (IGDD-2711, User Story 10).
 *
 * <p>When Config Console renews an API key (IGDD-2707) it issues a new credential and moves the old
 * one to status {@code grace_period} with a {@code graceExpiresAt}; it keeps authenticating alongside
 * the new key during the grace window. Once {@code graceExpiresAt} passes, the old key must be revoked
 * so renewed keys do not accumulate as indefinitely-valid credentials. This job performs that sweep
 * inside Hub, reusing the credential repository and audit logger.</p>
 *
 * <p>The job is gated on {@code apikey.grace-revocation.enabled} (off by default) so it can be enabled
 * per environment. Spring's scheduling infrastructure is enabled globally by {@code @EnableScheduling}
 * on the application class, independent of this bean's conditional.</p>
 *
 * <p><b>Multi-instance safety (design D5):</b> every enabled instance runs the cycle; correctness does
 * not depend on electing a single runner. Each candidate is revoked via a <em>conditional</em> write
 * ({@link ApiKeyCredentialRepository#revokeIfGracePeriod}) that only succeeds while the key is still
 * {@code grace_period}, so a given key is revoked — and audited — exactly once even under concurrent
 * runs. (An earlier host-ordering election was removed: the host registry can retain stale hosts, which
 * could make a lone live instance defer to a ghost and never run.) Cross-instance cache propagation is
 * intentionally not broadcast — see {@link #evictLocalCache(String)}.</p>
 */
@Component
@Slf4j
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
            // Structured eventType so a CloudWatch Logs metric filter can match on the JSON field
            // ({ $.eventType = "GRACE_REVOCATION_FAILED" }) rather than a brittle message substring (AC #3).
            log.error(Markers2.append("eventType", "GRACE_REVOCATION_FAILED").and(Markers2.append(e)),
                    "Grace-period revocation cycle failed: {}", e.getMessage());
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
     * this environment, revoke each, emit an audit event, and evict the local cache. Emits a
     * {@code GRACE_REVOCATION_STARTED} log at the beginning and a {@code GRACE_REVOCATION_RUN} log on
     * successful completion (with the number of credentials evaluated and revoked); a failure is
     * logged as {@code GRACE_REVOCATION_FAILED} by {@link #scheduledRun()}. These three events let
     * operations see that a run started and whether it succeeded or failed (AC #4; alarms may be
     * layered on these log events later).
     */
    void runRevocationCycle() {
        // Records are keyed by the numeric environment (e.g. "5"), matching ApiKeyPrincipalProvider's
        // String.valueOf(envInt) and the {env}#{jti} sort key — NOT the human-readable dest-type name.
        String env = String.valueOf(SystemUtils.getDestType());
        log.info(Markers2.append("eventType", "GRACE_REVOCATION_STARTED", "environment", env),
                "Grace-period revocation cycle started");

        List<ApiKeyCredential> candidates = credentialRepository.findGraceRevocationCandidates(env);

        int evaluated = candidates.size();
        int revoked = 0;
        for (ApiKeyCredential credential : candidates) {
            if (revokeCredential(credential)) {
                revoked++;
            }
        }

        log.info(Markers2.append(
                "eventType", "GRACE_REVOCATION_RUN",
                "environment", env,
                "evaluated", evaluated,
                "revoked", revoked
        ), "Grace-period revocation cycle succeeded: evaluated={}, revoked={}", evaluated, revoked);
    }

    /**
     * Revoke a single superseded credential via a conditional write (revoke only if still
     * {@code grace_period}). On the write that actually performs the revocation, emit the
     * {@code API_KEY_REVOKED} audit event and evict the credential from this instance's local cache.
     * If another instance already revoked it (conditional write fails), do nothing — so the audit
     * event fires exactly once across the fleet.
     *
     * @param credential the grace-expired candidate to revoke
     * @return {@code true} if this call performed the revocation; {@code false} otherwise
     */
    private boolean revokeCredential(ApiKeyCredential credential) {
        boolean revoked = credentialRepository.revokeIfGracePeriod(
                credential, Instant.now(), ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION);
        if (!revoked) {
            return false;
        }

        String jti = credential.getJti();
        auditLogger.apiKeyRevoked(
                jti,
                credential.getJurisdictionId(),
                ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION,
                credential.getSupersededBy()
        );
        evictLocalCache(jti);
        return true;
    }

    /**
     * Evict the revoked credential from this instance's local cache so it stops serving the key
     * immediately.
     *
     * <p>Cross-instance broadcast is intentionally <em>not</em> performed here. Grace-period revocation
     * is non-urgent (the key has been winding down for the whole grace window), and every other instance
     * re-validates against DynamoDB when its credential-cache entry expires ({@code jwt.credential-cache-ttl},
     * default 5 minutes), at which point the now-{@code revoked} status takes effect — so the revocation
     * converges across the fleet within the cache TTL without any broadcast. Immediate all-instance
     * propagation is the concern of Config Console's manual revoke (IGDD-2707) via {@code /rest/refresh},
     * not of this scheduled sweep. See design D6.</p>
     *
     * @param jti the revoked credential identifier to evict locally
     */
    private void evictLocalCache(String jti) {
        apiKeyPrincipalProvider.evictCredential(jti);
    }
}
