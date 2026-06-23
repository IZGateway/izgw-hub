package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.security.IzgPrincipal;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Collection;
import java.util.TreeSet;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApiKeyPrincipal extends IzgPrincipal {

    private String jti;
    private String dns;

    public ApiKeyPrincipal(String sub, String jtiValue, Collection<String> jwtRoles, String dnsValue, String issuerValue) {
        setName(jtiValue);
        setOrganization(sub);
        setJti(jtiValue);
        setDns(dnsValue);
        setIssuer(issuerValue);
        setRoles(new TreeSet<>(jwtRoles));
    }

    @Override
    public String getSerialNumberHex() {
        return jti;
    }
}
