package gov.cdc.izgateway.hub.security;

import java.util.Collections;
import java.util.Set;

import gov.cdc.izgateway.security.IzgPrincipal;

/**
 * An {@link IzgPrincipal} produced by API-key (JWT) authentication.
 *
 * <p>Carries the jurisdiction (stored in the inherited {@code organization} field) and
 * the role set from the authenticated API key. {@code AccessControlValve} reads roles
 * via the inherited {@link IzgPrincipal#getRoles()} contract, so no changes to the
 * existing RBAC pipeline are required.</p>
 *
 * <p>{@link #getSerialNumberHex()} returns {@code null} because API-key principals are
 * not backed by an X.509 certificate.</p>
 */
public class ApiKeyPrincipal extends IzgPrincipal {

    /**
     * Constructs an API-key principal.
     *
     * @param name           the principal name (typically the JWT {@code jti}); may be {@code null}
     * @param jurisdictionId the jurisdiction the API key is issued to; stored as the
     *                       inherited {@code organization} value
     * @param roles          the roles granted to this key; {@code null} is treated as the empty set.
     *                       The set is defensively copied.
     */
    public ApiKeyPrincipal(String name, String jurisdictionId, Set<String> roles) {
        setName(name);
        setOrganization(jurisdictionId);
        setRoles(roles == null ? Collections.emptySet() : Set.copyOf(roles));
    }

    @Override
    public String getSerialNumberHex() {
        return null;
    }
}
