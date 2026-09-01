package gov.cdc.izgateway.hub.service;

import gov.cdc.izgateway.hub.security.ApiKeyAuthenticationException;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import gov.cdc.izgateway.principal.provider.CertificatePrincipalProvider;
import gov.cdc.izgateway.security.service.PrincipalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Hub implementation of PrincipalService. Resolves the caller identity by trying JWT (API key)
 * auth first, then falling back to TLS client certificate, then UnauthenticatedPrincipal.
 *
 * <p>The certificate fallback only applies when no API key was presented at all. If a Bearer-scheme
 * Authorization header was presented but failed authentication, that is a hard failure — it resolves
 * to {@link UnauthenticatedPrincipal} without attempting certificate authentication.
 *
 * @author Audacious Inquiry
 */
@Service
@Slf4j
public class HubPrincipalService implements PrincipalService {

    private final ApiKeyPrincipalProvider apiKeyPrincipalProvider;
    private final CertificatePrincipalProvider certificatePrincipalProvider;

    /**
     * Constructor
     * @param apiKeyPrincipalProvider  The API key (JWT) principal provider
     * @param certificatePrincipalProvider	 The certificate principal provider
     */
    @Autowired
    public HubPrincipalService(ApiKeyPrincipalProvider apiKeyPrincipalProvider, CertificatePrincipalProvider certificatePrincipalProvider) {
        this.apiKeyPrincipalProvider = apiKeyPrincipalProvider;
        this.certificatePrincipalProvider = certificatePrincipalProvider;
    }

    /**
     * Get the principal from the request. Tries API key (JWT) auth first, then cert auth, then unauthenticated.
     * <p>If an API key was presented but failed authentication, certificate authentication is not attempted —
     * the caller has identified itself as an API key holder, and a failed API key must not be silently
     * retried against mTLS.
     * @param request
     * @return The new principal
     */
    @Override
    public IzgPrincipal getPrincipal(HttpServletRequest request) {
        if (request != null) {
            IzgPrincipal principal;
            try {
                principal = apiKeyPrincipalProvider.getPrincipal(request);
            } catch (ApiKeyAuthenticationException e) {
                log.warn("API key present but failed authentication; not falling back to certificate auth: {}", e.getMessage());
                return new UnauthenticatedPrincipal();
            }
            if (principal != null) {
                return principal;
            }
            principal = certificatePrincipalProvider.createPrincipalFromCertificate(request);
            if (principal != null) {
                return principal;
            }
        }
        return new UnauthenticatedPrincipal();
    }
}