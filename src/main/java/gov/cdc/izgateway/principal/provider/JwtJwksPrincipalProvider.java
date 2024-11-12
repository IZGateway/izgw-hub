package gov.cdc.izgateway.principal.provider;

import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.JWTPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Objects;

@Slf4j
@Component
public class JwtJwksPrincipalProvider implements JwtPrincipalProvider {
    @Value("${jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Override
    public IzgPrincipal createPrincipalFromJwt(HttpServletRequest request) {
        if (StringUtils.isBlank(jwkSetUri)) {
            log.warn("No JWT set URI configured.  JWT authentication is disabled.");
            return null;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No JWT token found in Authorization header");
            return null;
        }

        JwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        Jwt jwt;

        try {
            String token = authHeader.substring(7);
            jwt = jwtDecoder.decode(token);
        } catch (Exception e) {
            log.warn("Error parsing JWT token", e);
            return null;
        }

        IzgPrincipal principal = new JWTPrincipal();
        principal.setName(jwt.getSubject());
        principal.setOrganization(jwt.getClaim("organization"));
        principal.setValidFrom(Date.from(Objects.requireNonNull(jwt.getIssuedAt())));
        principal.setValidTo(Date.from(Objects.requireNonNull(jwt.getExpiresAt())));
        principal.setSerialNumber(jwt.getId());
        principal.setIssuer(jwt.getIssuer().toString());
        principal.setAudience(jwt.getAudience());

        return principal;
    }
}
