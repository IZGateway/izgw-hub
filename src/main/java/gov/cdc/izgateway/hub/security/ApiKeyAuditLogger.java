package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.logging.markers.Markers2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Emits structured audit events for API-key (JWT) authentication outcomes (IGDD-2704).
 *
 * <p>Events are written as structured Logstash fields via {@link Markers2} so they flow
 * through Hub's existing JSON logging pipeline alongside the rest of the application's
 * security logging. Successful authentications are logged at INFO with event type
 * {@code API_KEY_USED}; failed attempts at WARN with event type {@code API_KEY_AUTH_FAILED}.</p>
 *
 * <p>This is a dedicated, injectable component (rather than inline logging in
 * {@link ApiKeyPrincipalProvider}) so the audit behaviour can be unit-tested and verified
 * independently of the JWT validation flow.</p>
 */
@Component
@Slf4j
public class ApiKeyAuditLogger {

    /** Audit event type emitted when an API key successfully authenticates a request. */
    public static final String API_KEY_USED = "API_KEY_USED";

    /** Audit event type emitted when an API-key authentication attempt fails. */
    public static final String API_KEY_AUTH_FAILED = "API_KEY_AUTH_FAILED";

    /** Audit event type emitted when an API key is revoked. */
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";

    /**
     * {@code revokedBy} value used when a credential is revoked automatically by the
     * grace-period revocation scheduled job (IGDD-2711), as opposed to a manual,
     * operator-driven revocation through Config Console.
     */
    public static final String SYSTEM_GRACE_REVOCATION = "system:grace-revocation";

    /**
     * Emit an {@code API_KEY_USED} audit event for a successful authentication.
     *
     * @param keyId          the API key identifier (JWT {@code jti})
     * @param jurisdictionId the jurisdiction the key authenticated as (JWT {@code sub})
     * @param sourceIp       the client source IP address
     */
    public void apiKeyUsed(String keyId, String jurisdictionId, String sourceIp) {
        log.info(Markers2.append(
                "eventType", API_KEY_USED,
                "keyId", keyId,
                "jurisdictionId", jurisdictionId,
                "sourceIp", sourceIp,
                "timestamp", Instant.now().toString()
        ), "API key authentication succeeded for jurisdiction {} (keyId={})", jurisdictionId, keyId);
    }

    /**
     * Emit an {@code API_KEY_AUTH_FAILED} audit event for a failed authentication attempt.
     *
     * @param keyId    the API key identifier (JWT {@code jti}) if parseable; otherwise {@code null}
     * @param sourceIp the client source IP address
     * @param reason   a short, non-sensitive description of why authentication failed
     */
    public void apiKeyAuthFailed(String keyId, String sourceIp, String reason) {
        log.warn(Markers2.append(
                "eventType", API_KEY_AUTH_FAILED,
                "keyId", keyId,
                "sourceIp", sourceIp,
                "failureReason", reason,
                "timestamp", Instant.now().toString()
        ), "API key authentication failed (keyId={}): {}", keyId, reason);
    }

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
}
