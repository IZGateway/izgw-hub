package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import gov.cdc.izgateway.security.service.PrincipalService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationEnforcementFilterTest {

    @Mock private PrincipalService principalService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    @InjectMocks
    private AuthenticationEnforcementFilter filter;

    @Test
    void unauthenticatedPrincipal_returns401_doesNotContinueChain() throws Exception {
        when(principalService.getPrincipal(request)).thenReturn(new UnauthenticatedPrincipal());

        filter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        verifyNoInteractions(filterChain);
    }

    @Test
    void healthEndpoint_isExemptFromAuthCheck() throws Exception {
        when(request.getServletPath()).thenReturn("/rest/health");

        boolean skipped = filter.shouldNotFilter(request);

        org.junit.jupiter.api.Assertions.assertTrue(skipped,
                "Health check endpoint must bypass auth filter to allow ALB health checks");
    }

    @Test
    void authenticatedPrincipal_continuesFilterChain() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal("ORG", "jti-123", java.util.List.of("ads"), "dns.example.gov", "http://issuer");
        when(principalService.getPrincipal(request)).thenReturn(principal);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }
}
