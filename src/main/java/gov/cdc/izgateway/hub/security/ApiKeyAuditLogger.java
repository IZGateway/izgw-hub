package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.logging.markers.Markers2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Emits structured audit events for API-key credential lifecycle actions.
 *
 * <p>Events are written as structured Logstash fields via {@link Markers2} so they flow through
 * Hub's existing JSON logging pipeline alongside the rest of the application's security logging.
 * This is a dedicated, injectable component so the audit behaviour can be unit-tested independently
 * of the code paths that trigger it.</p>
 *
 * <p>Currently this emits the {@code API_KEY_REVOKED} and {@code API_KEY_EXPIRED} events used by the
 * grace-period revocation job (IGDD-2711, IGDD-3167). Authentication audit events
 * ({@code API_KEY_USED} / {@code API_KEY_AUTH_FAILED}) are introduced by IGDD-2704, wired into
 * {@link ApiKeyPrincipalProvider} in that change.</p>
 */
@Component
@Slf4j
public class ApiKeyAuditLogger {

    /** Audit event type emitted when an API key is revoked (cut off before its own expiry). */
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";

    /** Audit event type emitted when an API key is marked expired (reached its own expiry). */
    public static final String API_KEY_EXPIRED = "API_KEY_EXPIRED";

    /**
     * {@code revokedBy} value used when a credential is revoked automatically by the
     * grace-period revocation scheduled job (IGDD-2711), as opposed to a manual,
     * operator-driven revocation through Config Console.
     */
    public static final String SYSTEM_GRACE_REVOCATION = "system:grace-revocation";

    /**
     * {@code expiredBy} value used when a credential is marked expired automatically by the
     * grace-period revocation scheduled job because it reached its own {@code expiresAt} on or
     * before its {@code graceExpiresAt} (IGDD-3167).
     */
    public static final String SYSTEM_GRACE_EXPIRATION = "system:grace-expiration";

    /**
     * Emit an {@code API_KEY_REVOKED} audit event for a credential revocation.
     *
     * <p>Used by the grace-period revocation scheduled job (IGDD-2711) when a superseded
     * credential is revoked after its grace period expires. The event carries no token or
     * secret material.</p>
     *
     * @param keyId          the revoked API key identifier (JWT {@code jti})
     * @param jurisdictionId the jurisdiction the credential belonged to
     * @param revokedBy      the identity performing the revocation (e.g. {@link #SYSTEM_GRACE_REVOCATION})
     * @param supersededBy   the {@code jti} of the renewed credential that superseded this one; may be {@code null}
     */
    public void apiKeyRevoked(String keyId, String jurisdictionId, String revokedBy, String supersededBy) {
        log.info(Markers2.append(
                "eventType", API_KEY_REVOKED,
                "keyId", keyId,
                "jurisdictionId", jurisdictionId,
                "revokedBy", revokedBy,
                "supersededBy", supersededBy
        ).and(Markers2.append("timestamp", Instant.now().toString())),
                "API key revoked for jurisdiction {} (keyId={}, revokedBy={})", jurisdictionId, keyId, revokedBy);
    }

    /**
     * Emit an {@code API_KEY_EXPIRED} audit event for a credential that reached its own expiry.
     *
     * <p>Used by the grace-period revocation scheduled job (IGDD-3167) when a superseded
     * credential's own {@code expiresAt} was on or before its {@code graceExpiresAt} — the JWT
     * {@code exp} capped its validity before the grace window would have. The event carries no
     * token or secret material.</p>
     *
     * @param keyId          the expired API key identifier (JWT {@code jti})
     * @param jurisdictionId the jurisdiction the credential belonged to
     * @param expiredBy      the identity recording the expiry (e.g. {@link #SYSTEM_GRACE_EXPIRATION})
     * @param supersededBy   the {@code jti} of the renewed credential that superseded this one; may be {@code null}
     */
    public void apiKeyExpired(String keyId, String jurisdictionId, String expiredBy, String supersededBy) {
        log.info(Markers2.append(
                "eventType", API_KEY_EXPIRED,
                "keyId", keyId,
                "jurisdictionId", jurisdictionId,
                "expiredBy", expiredBy,
                "supersededBy", supersededBy
        ).and(Markers2.append("timestamp", Instant.now().toString())),
                "API key expired for jurisdiction {} (keyId={}, expiredBy={})", jurisdictionId, keyId, expiredBy);
    }
}
