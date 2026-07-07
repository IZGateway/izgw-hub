package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApiKeyPrincipal extends IzgPrincipal {

    private String jti;
    private String upn;

    public ApiKeyPrincipal(String sub, String jtiValue, String upnValue, String issuerValue) {
        setName(upnValue);
        setOrganization(sub);
        setJti(jtiValue);
        setUpn(upnValue);
        setIssuer(issuerValue);
    }

    @Override
    public String getSerialNumberHex() {
        return jti;
    }
}
