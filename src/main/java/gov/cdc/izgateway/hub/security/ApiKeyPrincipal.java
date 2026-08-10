package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApiKeyPrincipal extends IzgPrincipal {

    private String jti;
    private String upn;
    /**
     * The use-types this credential may submit, copied from the {@code ApiKeyCredential} record at
     * authentication time (i.e. read by {@code jti}) and therefore refreshed on the credential cache's
     * TTL. Intersected with the destination jurisdiction's {@code allowedUseTypes} at routing time
     * (IGDD-3257). Empty or {@code null} authorizes nothing.
     */
    private Set<String> useTypes;

    public ApiKeyPrincipal(String sub, String jtiValue, String upnValue, String issuerValue) {
        this(sub, jtiValue, upnValue, issuerValue, null);
    }

    public ApiKeyPrincipal(String sub, String jtiValue, String upnValue, String issuerValue,
            Set<String> useTypesValue) {
        setName(upnValue);
        setOrganization(sub);
        setJti(jtiValue);
        setUpn(upnValue);
        setIssuer(issuerValue);
        setUseTypes(useTypesValue);
    }

    @Override
    public String getSerialNumberHex() {
        return jti;
    }
}
