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
import java.util.Set;

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

    /**
     * Health check endpoints the ALB target group and the container probe reach without a
     * credential. Matched exactly, for the same reason as
     * {@code AccessControlValve.HEALTH_CHECK_PATHS}: a prefix match hands this exemption to
     * anything else that happens to start with {@code /rest/health}, which is more than the
     * probes need.
     */
    private static final Set<String> HEALTH_CHECK_PATHS = Set.of("/rest/health", "/rest/healthy");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_CHECK_PATHS.contains(request.getServletPath());
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
