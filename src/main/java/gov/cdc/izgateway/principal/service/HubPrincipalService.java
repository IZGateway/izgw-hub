package gov.cdc.izgateway.principal.service;

import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import gov.cdc.izgateway.principal.provider.CertificatePrincipalProvider;
import gov.cdc.izgateway.principal.provider.JwtPrincipalProvider;
import gov.cdc.izgateway.security.service.PrincipalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class HubPrincipalService implements PrincipalService {

    private final JwtPrincipalProvider jwtPrincipalProvider;
    private final CertificatePrincipalProvider certificatePrincipalProvider;

    @Autowired
    public HubPrincipalService(JwtPrincipalProvider jwtPrincipalProvider, CertificatePrincipalProvider certificatePrincipalProvider) {
        this.jwtPrincipalProvider = jwtPrincipalProvider;
        this.certificatePrincipalProvider = certificatePrincipalProvider;
    }

    /**
     * Get the principal from the request. This will first try to get the principal from the JWT token,
     * and if that fails, it will try to get the principal from the certificate. If neither is found, it
     * will return an UnauthenticatedPrincipal.
     * @param request
     * @return
     */
    @Override
    public IzgPrincipal getPrincipal(HttpServletRequest request) {
        IzgPrincipal izgPrincipal = null;

        if (request != null) {
            izgPrincipal = jwtPrincipalProvider.createPrincipalFromJwt(request);
            if (izgPrincipal == null) {
                izgPrincipal = certificatePrincipalProvider.createPrincipalFromCertificate(request);
            }
        }

        if (izgPrincipal == null) {
            izgPrincipal = new UnauthenticatedPrincipal();
        }

        return izgPrincipal;
    }
}