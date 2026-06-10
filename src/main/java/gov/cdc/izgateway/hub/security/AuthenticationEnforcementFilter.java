package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import gov.cdc.izgateway.security.service.PrincipalService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns 401 for unauthenticated callers when server.ssl.client-auth=want.
 * Required when Hub accepts JWT-only clients (no TLS cert) — without this filter,
 * anonymous connections would reach business logic as UnauthenticatedPrincipal.
 */
@Component
@ConditionalOnProperty(name = "server.ssl.client-auth", havingValue = "want")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticationEnforcementFilter extends OncePerRequestFilter {

    private final PrincipalService principalService;

    @Autowired
    public AuthenticationEnforcementFilter(PrincipalService principalService) {
        this.principalService = principalService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (principalService.getPrincipal(request) instanceof UnauthenticatedPrincipal) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
