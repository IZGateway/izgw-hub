package gov.cdc.izgateway.hub.service.accesscontrol;

import gov.cdc.izgateway.hub.security.ApiKeyPrincipal;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code AccessControlService.useTypeViolation} — the routing-time use-type decision for API-key
 * callers (IGDD-3257). A non-null return always rejects: the calling method has no warn/permissive mode,
 * so a returned fault is always thrown.
 */
class UseTypeAccessControlTests {

    private static final String DEST_ID = "az";

    private static ApiKeyPrincipal principal(Set<String> useTypes) {
        return new ApiKeyPrincipal("42", "018f4e2a-5678-7abc-8def-000000000002",
                "test.example.gov", "http://localhost:3000", useTypes);
    }

    @Test
    @DisplayName("Intersecting use types produce no violation")
    void intersectingUseTypes_noViolation() {
        assertNull(AccessControlService.useTypeViolation(
                principal(Set.of("PROVIDER")), DEST_ID, Set.of("PROVIDER", "PATIENT")));
    }

    @Test
    @DisplayName("Disjoint use types produce a violation")
    void disjointUseTypes_produceViolation() {
        SecurityFault fault = AccessControlService.useTypeViolation(
                principal(Set.of("PATIENT")), DEST_ID, Set.of("PROVIDER"));
        assertNotNull(fault);
    }

    @Test
    @DisplayName("A jurisdiction with no allowedUseTypes denies every API-key sender")
    void emptyAllowedUseTypes_deniesAll() {
        assertNotNull(AccessControlService.useTypeViolation(
                principal(Set.of("PROVIDER")), DEST_ID, Set.of()));
        assertNotNull(AccessControlService.useTypeViolation(
                principal(Set.of("PROVIDER")), DEST_ID, null));
    }

    @Test
    @DisplayName("A credential with no useTypes is denied")
    void credentialWithoutUseTypes_isDenied() {
        assertNotNull(AccessControlService.useTypeViolation(principal(null), DEST_ID, Set.of("PROVIDER")));
        assertNotNull(AccessControlService.useTypeViolation(principal(Set.of()), DEST_ID, Set.of("PROVIDER")));
    }

    @Test
    @DisplayName("The fault identifies the credential, destination, and both use-type sets")
    void faultMessageIsDiagnostic() {
        SecurityFault fault = AccessControlService.useTypeViolation(
                principal(Set.of("PATIENT")), DEST_ID, Set.of("PROVIDER"));
        assertNotNull(fault);
        String detail = fault.getMessage() + " " + fault.getDetail();
        assertTrue(detail.contains("018f4e2a-5678-7abc-8def-000000000002"), "should name the jti: " + detail);
        assertTrue(detail.contains(DEST_ID), "should name the destination: " + detail);
        assertTrue(detail.contains("PATIENT"), "should report the credential useTypes: " + detail);
        assertTrue(detail.contains("PROVIDER"), "should report the allowedUseTypes: " + detail);
    }

    @Test
    @DisplayName("The fault reports a 4xx client error, not a server error")
    void faultIsClientError() {
        SecurityFault fault = AccessControlService.useTypeViolation(
                principal(Set.of("PATIENT")), DEST_ID, Set.of("PROVIDER"));
        assertNotNull(fault);
        // A use-type denial is the caller's problem, so it must not surface as a 5xx. The REST/ADS path
        // derives its status from the fault's RetryStrategy (see ExceptionHandling#handleFault), and
        // CORRECT_MESSAGE maps to 400. Note the SOAP path hardcodes 500 for every fault in izgw-core's
        // SoapControllerBase#handleFault — that is a separate, cross-repo concern.
        assertEquals(RetryStrategy.CORRECT_MESSAGE, fault.getRetry());
        assertTrue(fault.getRetry().getStatus().is4xxClientError(),
                "expected a 4xx status, got " + fault.getRetry().getStatus());
    }

    @Test
    @DisplayName("The fault carries no token or secret material")
    void faultCarriesNoSecrets() {
        SecurityFault fault = AccessControlService.useTypeViolation(
                principal(Set.of("PATIENT")), DEST_ID, Set.of("PROVIDER"));
        String detail = fault.getMessage() + " " + fault.getDetail();
        assertTrue(!detail.contains("Bearer") && !detail.contains("eyJ"), "must not contain token material");
    }
}
