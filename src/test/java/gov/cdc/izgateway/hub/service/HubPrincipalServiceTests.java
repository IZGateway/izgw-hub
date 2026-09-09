package gov.cdc.izgateway.hub.service;

import gov.cdc.izgateway.hub.security.ApiKeyAuthenticationException;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.principal.provider.CertificatePrincipalProvider;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HubPrincipalServiceTests {

    @Mock private ApiKeyPrincipalProvider apiKeyPrincipalProvider;
    @Mock private CertificatePrincipalProvider certificatePrincipalProvider;
    @Mock private HttpServletRequest request;

    private HubPrincipalService service;

    @BeforeEach
    void setUp() {
        service = new HubPrincipalService(apiKeyPrincipalProvider, certificatePrincipalProvider);
    }

    @Test
    void apiKeySucceeds_returnsApiKeyPrincipal_certNeverConsulted() {
        IzgPrincipal apiKeyPrincipal = mock(IzgPrincipal.class);
        when(apiKeyPrincipalProvider.getPrincipal(request)).thenReturn(apiKeyPrincipal);

        IzgPrincipal result = service.getPrincipal(request);

        assertThat(result).isSameAs(apiKeyPrincipal);
        verifyNoInteractions(certificatePrincipalProvider);
    }

    @Test
    void apiKeyPresentButInvalid_doesNotFallBackToCert() {
        when(apiKeyPrincipalProvider.getPrincipal(request))
                .thenThrow(new ApiKeyAuthenticationException("bad token"));

        IzgPrincipal result = service.getPrincipal(request);

        assertThat(result).isInstanceOf(UnauthenticatedPrincipal.class);
        verifyNoInteractions(certificatePrincipalProvider);
    }

    @Test
    void noApiKeyPresented_fallsBackToCert_certSucceeds() {
        when(apiKeyPrincipalProvider.getPrincipal(request)).thenReturn(null);
        IzgPrincipal certPrincipal = mock(IzgPrincipal.class);
        when(certificatePrincipalProvider.createPrincipalFromCertificate(request)).thenReturn(certPrincipal);

        IzgPrincipal result = service.getPrincipal(request);

        assertThat(result).isSameAs(certPrincipal);
    }

    @Test
    void noApiKeyPresented_noCert_returnsUnauthenticated() {
        when(apiKeyPrincipalProvider.getPrincipal(request)).thenReturn(null);
        when(certificatePrincipalProvider.createPrincipalFromCertificate(request)).thenReturn(null);

        IzgPrincipal result = service.getPrincipal(request);

        assertThat(result).isInstanceOf(UnauthenticatedPrincipal.class);
    }

    @Test
    void nullRequest_returnsUnauthenticated() {
        IzgPrincipal result = service.getPrincipal(null);

        assertThat(result).isInstanceOf(UnauthenticatedPrincipal.class);
        verifyNoInteractions(apiKeyPrincipalProvider, certificatePrincipalProvider);
    }
}
