package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyPrincipalTest {

    private static final String SUB = "TEST_ORG";
    private static final String JTI = "0d0fa1d2-3c5d-4e2a-9a5c-1f7b4d2e9c10";
    private static final String UPN = "test.example.gov";
    private static final String ISSUER = "http://localhost:3000";

    private ApiKeyPrincipal principal() {
        return new ApiKeyPrincipal(SUB, JTI, UPN, ISSUER);
    }

    @Test
    void rolesAreAlwaysEmpty() {
        assertThat(principal().getRoles()).isEmpty();
    }

    @Test
    void subjectIsExposedAsOrganization() {
        assertThat(principal().getOrganization()).isEqualTo(SUB);
    }

    @Test
    void upnIsCarriedAsName() {
        assertThat(principal().getName()).isEqualTo(UPN);
    }

    @Test
    void jtiFieldIsSet() {
        assertThat(principal().getJti()).isEqualTo(JTI);
    }

    @Test
    void upnFieldIsSet() {
        assertThat(principal().getUpn()).isEqualTo(UPN);
    }

    @Test
    void issuerFieldIsSet() {
        assertThat(principal().getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void isInstanceOfIzgPrincipalAndJavaPrincipal() {
        assertThat(principal()).isInstanceOf(IzgPrincipal.class);
        assertThat(principal()).isInstanceOf(Principal.class);
    }

    @Test
    void serialNumberHexReturnsJti() {
        // ApiKeyPrincipal uses jti as the serial number equivalent (differs from cert-backed principals)
        assertThat(principal().getSerialNumberHex()).isEqualTo(JTI);
    }

    @Test
    void nullSubjectIsTolerated() {
        ApiKeyPrincipal p = new ApiKeyPrincipal(null, JTI, UPN, ISSUER);

        assertThat(p.getOrganization()).isNull();
    }
}
