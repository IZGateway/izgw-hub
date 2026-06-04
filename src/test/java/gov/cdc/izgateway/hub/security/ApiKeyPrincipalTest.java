package gov.cdc.izgateway.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Principal;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.Roles;

/**
 * Unit tests for {@link ApiKeyPrincipal}.
 *
 * <p>Verifies the acceptance criteria for IGDD-2706: roles round-trip via the inherited
 * {@code getRoles()} contract, jurisdiction is exposed via {@code getOrganization()},
 * and the type is assignable to {@link IzgPrincipal} so {@code AccessControlValve}
 * accepts it without modification.</p>
 */
class ApiKeyPrincipalTest {

    private static final String JTI = "0d0fa1d2-3c5d-4e2a-9a5c-1f7b4d2e9c10";
    private static final String JURISDICTION = "CA";

    @Test
    void rolesAreReturnedInTheFormatAccessControlValveExpects() {
        Set<String> input = Set.of(Roles.SOAP, Roles.USERS);

        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, input);

        Set<String> roles = principal.getRoles();
        assertEquals(input, roles, "roles should round-trip unchanged");
        assertTrue(roles.contains(Roles.SOAP));
        assertTrue(roles.contains(Roles.USERS));
    }

    @Test
    void jurisdictionIsExposedAsOrganization() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, Set.of(Roles.SOAP));

        assertEquals(JURISDICTION, principal.getOrganization());
    }

    @Test
    void nameIsCarriedThrough() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, Set.of());

        assertEquals(JTI, principal.getName());
    }

    @Test
    void isInstanceOfIzgPrincipalSoNoHubLogicNeedsModification() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, Set.of(Roles.SOAP));

        assertTrue(principal instanceof IzgPrincipal,
                "must be an IzgPrincipal so existing instanceof checks pass");
        assertTrue(principal instanceof Principal,
                "must satisfy the java.security.Principal contract");
    }

    @Test
    void nullRolesBecomeAnEmptySet() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, null);

        assertEquals(Set.of(), principal.getRoles(), "null roles should yield an empty set, not NPE");
    }

    @Test
    void rolesAreDefensivelyCopiedFromTheConstructorArgument() {
        Set<String> mutable = new HashSet<>();
        mutable.add(Roles.SOAP);

        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, mutable);

        mutable.add(Roles.ADMIN);
        assertFalse(principal.getRoles().contains(Roles.ADMIN),
                "mutating the caller's set must not mutate the principal's roles");
    }

    @Test
    void rolesViewIsImmutable() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, Set.of(Roles.SOAP));

        assertThrows(UnsupportedOperationException.class,
                () -> principal.getRoles().add(Roles.ADMIN),
                "callers must not be able to mutate the principal's role set");
    }

    @Test
    void serialNumberHexIsNullBecauseNoCertificateBacksThePrincipal() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, JURISDICTION, Set.of(Roles.SOAP));

        assertNull(principal.getSerialNumberHex());
    }

    @Test
    void nullJurisdictionIsTolerated() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(JTI, null, Set.of(Roles.SOAP));

        assertNull(principal.getOrganization());
    }
}
