package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

/**
 * The principal for a request that authenticates with an API key (a signed JWT).
 */
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

    /**
     * Create an API key principal with no use-types.
     * @param sub	The JWT subject, used as the organization
     * @param jtiValue	The JWT identifier of the credential
     * @param upnValue	The user principal name, used as the name
     * @param issuerValue	The JWT issuer
     */
    public ApiKeyPrincipal(String sub, String jtiValue, String upnValue, String issuerValue) {
        this(sub, jtiValue, upnValue, issuerValue, null);
    }

    /**
     * Create an API key principal.
     * @param sub	The JWT subject, used as the organization
     * @param jtiValue	The JWT identifier of the credential
     * @param upnValue	The user principal name, used as the name
     * @param issuerValue	The JWT issuer
     * @param useTypesValue	The use-types this credential can submit
     */
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
