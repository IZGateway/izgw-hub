package gov.cdc.izgateway.hub.service;

import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import gov.cdc.izgateway.principal.provider.CertificatePrincipalProvider;
import gov.cdc.izgateway.security.service.PrincipalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Hub implementation of the PrincipalService. This implementation gets the principal from 
 * the certificate in the request.
 * 
 * @author Audacious Inquiry
 *
 */
@Service
@Slf4j
public class HubPrincipalService implements PrincipalService {

    private final CertificatePrincipalProvider certificatePrincipalProvider;

    /**
     * Constructor
     * @param certificatePrincipalProvider	 The certificate principal provider
     */
    @Autowired
    public HubPrincipalService(CertificatePrincipalProvider certificatePrincipalProvider) {
        this.certificatePrincipalProvider = certificatePrincipalProvider;
    }

    /**
     * Get the principal from the request. This will first try to get the principal from the certificate, if that fails, it will return an UnauthenticatedPrincipal.
     * @param request
     * @return The new principal
     */
    @Override
    public IzgPrincipal getPrincipal(HttpServletRequest request) {
        IzgPrincipal izgPrincipal = null;

        if (request != null) {
            izgPrincipal = certificatePrincipalProvider.createPrincipalFromCertificate(request);
        }

        if (izgPrincipal == null) {
            izgPrincipal = new UnauthenticatedPrincipal();
        }

        return izgPrincipal;
    }
}