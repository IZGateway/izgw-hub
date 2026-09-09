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
 * Scheduled job that terminates superseded API-key credentials after their grace period expires
 * (IGDD-2711, User Story 10), recording the correct terminal status (IGDD-3167).
 *
 * <p>When Config Console renews an API key (IGDD-2707) it issues a new credential and moves the old
 * one to status {@code grace_period} with a {@code graceExpiresAt}; it keeps authenticating alongside
 * the new key during the grace window. Once {@code graceExpiresAt} passes, the old key must be
 * terminated so renewed keys do not accumulate as indefinitely-valid credentials. Per the credential
 * state model the effective grace end is {@code min(graceExpiresAt, expiresAt)}: a key whose own
 * {@code expiresAt} was reached first is recorded {@code expired}; a key cut off before its own expiry
 * is recorded {@code revoked} ({@link ApiKeyCredentialRepository#resolveTerminalStatus}). This job
 * performs that sweep inside Hub, reusing the credential repository and audit logger.</p>
 *
 * <p>The job is gated on {@code apikey.grace-revocation.enabled} (off by default) so it can be enabled
 * per environment. Spring's scheduling infrastructure is enabled globally by {@code @EnableScheduling}
 * on the application class, independent of this bean's conditional.</p>
 *
 * <p><b>Multi-instance safety (design D5):</b> every enabled instance runs the cycle; correctness does
 * not depend on electing a single runner. Each candidate is terminated via a <em>conditional</em> write
 * ({@link ApiKeyCredentialRepository#terminateIfGracePeriod}) that only succeeds while the key is still
 * {@code grace_period}, so a given key is terminated — and audited — exactly once even under concurrent
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
     * Execute one revocation cycle: find every superseded credential whose grace period has expired,
     * terminate each to its correct status ({@code expired} or {@code revoked}, IGDD-3167), emit the
     * matching audit event, and evict the local cache. The sweep is NOT scoped to this Hub's
     * environment -- see {@link #runRevocationCycle()} for why -- so the {@code environment} and
     * {@code serverName} fields on the log and audit events identify the instance that performed a
     * termination, not the set of credentials considered. Emits a
     * {@code GRACE_REVOCATION_STARTED} log at the beginning and a {@code GRACE_REVOCATION_RUN} log on
     * successful completion (with the number of credentials evaluated, expired, and revoked); a
     * failure is logged as {@code GRACE_REVOCATION_FAILED} by {@link #scheduledRun()}. These three
     * events let operations see that a run started and whether it succeeded or failed (AC #4; alarms
     * may be layered on these log events later).
     */
    CycleResult runRevocationCycle() {
        // The credential sort key is {jti} with no environment prefix (IGDD-3140), so there is no prefix
        // left to scope a query by: the sweep evaluates every ApiKeyCredential record. A key's permitted
        // environments are a server-side `environments` set, not part of the key.
        //
        // The DynamoDB table is shared across environments (dev and test both use izgateway-dev-test),
        // so any enabled Hub may terminate a credential belonging to another environment. That is
        // harmless -- grace expiry is environment-independent and the conditional write keeps it
        // exactly-once -- but it does mean a termination cannot be attributed without recording who did
        // it, hence `environment`/`serverName` below and on the audit events (IGDD-3257 review).
        log.info(Markers2.append("eventType", "GRACE_REVOCATION_STARTED",
                        "environment", SystemUtils.getDestTag(),
                        "serverName", SystemUtils.getHostname()),
                "Grace-period revocation cycle started");

        List<ApiKeyCredential> candidates = credentialRepository.findGraceRevocationCandidates();

        int evaluated = candidates.size();
        int expired = 0;
        int revoked = 0;
        for (ApiKeyCredential credential : candidates) {
            try {
                String terminalStatus = terminateCredential(credential);
                if (ApiKeyCredentialRepository.STATUS_EXPIRED.equals(terminalStatus)) {
                    expired++;
                } else if (ApiKeyCredentialRepository.STATUS_REVOKED.equals(terminalStatus)) {
                    revoked++;
                }
            } catch (Exception e) {  // NOSONAR — one bad credential must not abort the rest of the sweep
                log.warn(Markers2.append("eventType", "GRACE_REVOCATION_ERROR", "keyId", credential.getJti())
                                .and(Markers2.append(e)),
                        "Failed to terminate credential {}: {}", credential.getJti(), e.getMessage());
            }
        }

        log.info(Markers2.append(
                "eventType", "GRACE_REVOCATION_RUN",
                "environment", SystemUtils.getDestTag(),
                "serverName", SystemUtils.getHostname(),
                "evaluated", evaluated,
                "expired", expired,
                "revoked", revoked
        ), "Grace-period revocation cycle succeeded: evaluated={}, expired={}, revoked={}",
                evaluated, expired, revoked);
        return new CycleResult(evaluated, expired, revoked);
    }

    /** Outcome of one revocation cycle: candidates evaluated, and how many were marked expired vs revoked. */
    record CycleResult(int evaluated, int expired, int revoked) {
    }

    /**
     * Terminate a single superseded credential via a conditional write (terminate only if still
     * {@code grace_period}). The terminal status ({@code expired} vs {@code revoked}) is resolved from
     * the credential's own {@code expiresAt}/{@code graceExpiresAt} up front (IGDD-3167), so the actor
     * value written and the audit event emitted match the write the repository performs. On the write
     * that actually performs the termination, emit the matching audit event and evict the credential
     * from this instance's local cache. If another instance already terminated it (conditional write
     * fails), do nothing — so the audit event fires exactly once across the fleet.
     *
     * @param credential the grace-expired candidate to terminate
     * @return the terminal status applied ({@link ApiKeyCredentialRepository#STATUS_EXPIRED} or
     *         {@link ApiKeyCredentialRepository#STATUS_REVOKED}) if this call performed the
     *         termination; {@code null} if the conditional write lost
     */
    private String terminateCredential(ApiKeyCredential credential) {
        String terminalStatus = ApiKeyCredentialRepository.resolveTerminalStatus(credential);
        boolean expired = ApiKeyCredentialRepository.STATUS_EXPIRED.equals(terminalStatus);
        String actor = expired ? ApiKeyAuditLogger.SYSTEM_GRACE_EXPIRATION : ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION;

        boolean won = credentialRepository.terminateIfGracePeriod(credential, Instant.now(), actor);
        if (!won) {
            return null;
        }

        String jti = credential.getJti();
        if (expired) {
            auditLogger.apiKeyExpired(jti, credential.getJurisdictionId(), actor, credential.getSupersededBy());
        } else {
            auditLogger.apiKeyRevoked(jti, credential.getJurisdictionId(), actor, credential.getSupersededBy());
        }
        evictLocalCache(jti);
        return terminalStatus;
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
