package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyPrincipalTest {

    private static final String SUB = "TEST_ORG";
    private static final String JTI = "0d0fa1d2-3c5d-4e2a-9a5c-1f7b4d2e9c10";
    private static final String DNS = "test.example.gov";
    private static final String ISSUER = "http://localhost:3000";

    private ApiKeyPrincipal principal(List<String> roles) {
        return new ApiKeyPrincipal(SUB, JTI, roles, DNS, ISSUER);
    }

    @Test
    void rolesAreReturnedInTheFormatAccessControlValveExpects() {
        ApiKeyPrincipal p = principal(List.of("ads", "soap"));

        assertThat(p.getRoles()).containsExactlyInAnyOrder("ads", "soap");
    }

    @Test
    void subjectIsExposedAsOrganization() {
        assertThat(principal(List.of()).getOrganization()).isEqualTo(SUB);
    }

    @Test
    void jtiIsCarriedAsName() {
        assertThat(principal(List.of()).getName()).isEqualTo(JTI);
    }

    @Test
    void jtiFieldIsSet() {
        assertThat(principal(List.of()).getJti()).isEqualTo(JTI);
    }

    @Test
    void dnsFieldIsSet() {
        assertThat(principal(List.of()).getDns()).isEqualTo(DNS);
    }

    @Test
    void issuerFieldIsSet() {
        assertThat(principal(List.of()).getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void isInstanceOfIzgPrincipalAndJavaPrincipal() {
        ApiKeyPrincipal p = principal(List.of("ads"));

        assertThat(p).isInstanceOf(IzgPrincipal.class);
        assertThat(p).isInstanceOf(Principal.class);
    }

    @Test
    void serialNumberHexReturnsJti() {
        // ApiKeyPrincipal uses jti as the serial number equivalent (differs from cert-backed principals)
        assertThat(principal(List.of()).getSerialNumberHex()).isEqualTo(JTI);
    }

    @Test
    void emptyRolesYieldsEmptySet() {
        assertThat(principal(List.of()).getRoles()).isEmpty();
    }

    @Test
    void nullSubjectIsTolerated() {
        ApiKeyPrincipal p = new ApiKeyPrincipal(null, JTI, List.of("ads"), DNS, ISSUER);

        assertThat(p.getOrganization()).isNull();
    }
}
