// JwtPrincipalProvider.java
package gov.cdc.izgateway.principal.provider;

import gov.cdc.izgateway.security.IzgPrincipal;
import jakarta.servlet.http.HttpServletRequest;

public interface JwtPrincipalProvider {
    IzgPrincipal createPrincipalFromJwt(HttpServletRequest request);
}