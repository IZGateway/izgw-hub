package gov.cdc.izgateway.hub.service;

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
     * @param request
     * @return The new principal
     */
    @Override
    public IzgPrincipal getPrincipal(HttpServletRequest request) {
        if (request != null) {
            IzgPrincipal principal = apiKeyPrincipalProvider.getPrincipal(request);
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