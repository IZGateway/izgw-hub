// CertificatePrincipalProvider.java
package gov.cdc.izgateway.principal.provider;

import gov.cdc.izgateway.security.IzgPrincipal;
import jakarta.servlet.http.HttpServletRequest;

public interface CertificatePrincipalProvider {
    IzgPrincipal createPrincipalFromCertificate(HttpServletRequest request);
}