package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository;
import gov.cdc.izgateway.logging.event.EventId;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Scheduled job that revokes superseded API-key credentials after their grace period expires
 * (IGDD-2711, User Story 10).
 *
 * <p>When Config Console renews an API key (IGDD-2707) it issues a new credential and stamps the
 * old one with {@code supersededBy} and {@code graceExpiresAt}, leaving it {@code active} so both
 * keys authenticate during the grace window. Once {@code graceExpiresAt} passes, the old key must
 * be revoked so renewed keys do not accumulate as indefinitely-valid credentials. This job performs
 * that sweep inside Hub, reusing the credential repository and audit logger built for IGDD-2705.</p>
 *
 * <p>The job is gated on {@code apikey.grace-revocation.enabled} (off by default) so it can be
 * enabled per environment. {@link EnableScheduling} is declared here so Spring's scheduling
 * infrastructure is only activated when this bean is present.</p>
 *
 * <p>In a multi-instance deployment a single instance performs the cycle, elected by host-ordering
 * (see {@link #isDesignatedRunner()}, design D5). Cross-instance cache propagation is intentionally not
 * broadcast (out of scope for grace revocation — see {@link #evictLocalCache(String)}).</p>
 */
@Component
@Slf4j
@EnableScheduling
@ConditionalOnProperty(prefix = "apikey.grace-revocation", name = "enabled", havingValue = "true")
public class GracePeriodRevocationScheduler {

    private final ApiKeyCredentialRepository credentialRepository;
    private final ApiKeyAuditLogger auditLogger;
    private final ApiKeyPrincipalProvider apiKeyPrincipalProvider;
    private final IHostRepository hostRepository;

    @Autowired
    public GracePeriodRevocationScheduler(
            ApiKeyCredentialRepository credentialRepository,
            ApiKeyAuditLogger auditLogger,
            ApiKeyPrincipalProvider apiKeyPrincipalProvider,
            IHostRepository hostRepository
    ) {
        this.credentialRepository = credentialRepository;
        this.auditLogger = auditLogger;
        this.apiKeyPrincipalProvider = apiKeyPrincipalProvider;
        this.hostRepository = hostRepository;
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
        if (!isDesignatedRunner()) {
            log.debug("Skipping grace-period revocation cycle: this instance is not the designated runner");
            return;
        }

        String env = SystemUtils.getDestTypeAsString();
        log.info(Markers2.append("eventType", "GRACE_REVOCATION_STARTED", "environment", env),
                "Grace-period revocation cycle started");

        List<ApiKeyCredential> candidates = credentialRepository.findGraceRevocationCandidates(env);

        int evaluated = candidates.size();
        int revoked = 0;
        for (ApiKeyCredential credential : candidates) {
            // Idempotency guard: only revoke a credential still in its grace period (skip anything
            // already revoked/changed since the query).
            if (!"grace_period".equals(credential.getStatus())) {
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
        ), "Grace-period revocation cycle succeeded: evaluated={}, revoked={}", evaluated, revoked);
    }

    /**
     * Revoke a single superseded credential: transition it to {@code revoked} in DynamoDB, emit the
     * {@code API_KEY_REVOKED} audit event, and evict it from this instance's local cache.
     *
     * @param credential the active, grace-expired credential to revoke
     */
    private void revokeCredential(ApiKeyCredential credential) {
        String jti = credential.getJti();

        credential.setStatus("revoked");
        credential.setRevokedAt(Instant.now());
        credential.setRevokedBy(ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION);
        credentialRepository.store(credential);

        auditLogger.apiKeyRevoked(
                jti,
                credential.getJurisdictionId(),
                ApiKeyAuditLogger.SYSTEM_GRACE_REVOCATION,
                credential.getSupersededBy()
        );

        evictLocalCache(jti);
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

    /**
     * Determine whether this instance should perform the revocation cycle, so that in a multi-instance
     * deployment a single instance acts per cycle (avoiding duplicate writes and audit noise).
     *
     * <p>Election is by host-ordering (design D5), consistent with {@code StatusCheckScheduler}: each
     * instance independently picks the lowest hostname among the currently-registered running instances
     * (from {@link IHostRepository#getHostsAndRegion()}) and runs only if that is itself. If the host
     * registry is empty/unconfigured (e.g. local/dev), this instance includes itself and therefore runs.
     * Because revocation is idempotent, transient registry disagreement between instances is harmless (a
     * key revoked twice is a no-op; a cycle skipped by all instances is retried next interval).</p>
     *
     * @return {@code true} if this instance should run the cycle
     */
    private boolean isDesignatedRunner() {
        return isDesignatedRunner(SystemUtils.getHostname(), hostRepository.getHostsAndRegion().keySet());
    }

    /**
     * Pure election logic, extracted for testability: the designated runner is the lowest hostname
     * (natural order) among {@code runningHosts} together with {@code me}.
     *
     * @param me           this instance's hostname
     * @param runningHosts the hostnames of currently-registered running instances (may be empty)
     * @return {@code true} if {@code me} is the elected runner
     */
    static boolean isDesignatedRunner(String me, Collection<String> runningHosts) {
        TreeSet<String> hosts = new TreeSet<>(runningHosts);
        hosts.add(me);
        return me.equals(hosts.first());
    }
}
