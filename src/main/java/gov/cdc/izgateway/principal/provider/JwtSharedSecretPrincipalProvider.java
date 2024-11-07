//package gov.cdc.izgateway.principal.provider;
//
//import gov.cdc.izgateway.security.IzgPrincipal;
//import gov.cdc.izgateway.security.JWTPrincipal;
//import io.jsonwebtoken.Claims;
//import io.jsonwebtoken.Jwts;
//import io.jsonwebtoken.security.Keys;
//import jakarta.servlet.http.HttpServletRequest;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//
//import javax.crypto.SecretKey;
//import java.util.Collections;
//import java.util.TreeSet;
//
//@Slf4j
//@Component
//public class JwtSharedSecretPrincipalProvider implements JwtPrincipalProvider {
//    @Value("${jwt.shared-secret:}")
//    private String sharedSecret;
//
//    @Override
//    public IzgPrincipal createPrincipalFromJwt(HttpServletRequest request) {
//
//        if (StringUtils.isBlank(sharedSecret)) {
//            log.warn("No JWT shared scret was set.  JWT authentication is disabled.");
//            return null;
//        }
//
//        String authHeader = request.getHeader("Authorization");
//        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
//            log.debug("No JWT token found in Authorization header");
//            return null;
//        }
//
//        Claims claims = null;
//        try {
//            SecretKey secretKey = Keys.hmacShaKeyFor(sharedSecret.getBytes());
//            String token = authHeader.substring(7);
//            claims = Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token).getBody();
//            log.debug("JWT claims for current request: {}", claims);
//        } catch (Exception e) {
//            log.warn("Error parsing JWT token", e);
//            return null;
//        }
//
//        IzgPrincipal principal = new JWTPrincipal();
//        principal.setName(claims.getSubject());
//        principal.setOrganization(claims.get("organization", String.class));
//        principal.setValidFrom(claims.getNotBefore());
//        principal.setValidTo(claims.getExpiration());
//        principal.setSerialNumber(claims.getId());
//        principal.setIssuer(claims.getIssuer());
//        principal.setAudience(Collections.singletonList(claims.getAudience()));
//
//        TreeSet<String> scopes = new TreeSet<>();
//        String scopeString = claims.get("scope", String.class);
//        if (!StringUtils.isEmpty(scopeString)) {
//            Collections.addAll(scopes, scopeString.split(" "));
//        }
//
//        principal.setRoles(RoleMapper.mapScopesToRoles(scopes));
//
//        return principal;
//    }
//}
//
////////
package gov.cdc.izgateway.principal.provider;

import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.JWTPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Collections;
import java.util.TreeSet;

@Slf4j
@Component
public class JwtSharedSecretPrincipalProvider implements JwtPrincipalProvider {
    @Value("${jwt.shared-secret:}")
    private String sharedSecret;

    @Override
    public IzgPrincipal createPrincipalFromJwt(HttpServletRequest request) {
        if (StringUtils.isBlank(sharedSecret)) {
            log.warn("No JWT shared secret was set. JWT authentication is disabled.");
            return null;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No JWT token found in Authorization header");
            return null;
        }

        Claims claims = parseJwt(authHeader);
        if (claims == null) {
            return null;
        }
        log.debug("JWT claims for current request: {}", claims);

        return buildPrincipal(claims);
    }

    private Claims parseJwt(String authHeader) {
        try {
            SecretKey secretKey = Keys.hmacShaKeyFor(sharedSecret.getBytes());
            String token = authHeader.substring(7);
            return Jwts.parserBuilder().setSigningKey(secretKey).build().parseClaimsJws(token).getBody();
        } catch (Exception e) {
            log.warn("Error parsing JWT token", e);
            return null;
        }
    }

    private IzgPrincipal buildPrincipal(Claims claims) {
        IzgPrincipal principal = new JWTPrincipal();
        principal.setName(claims.getSubject());
        principal.setOrganization(claims.get("organization", String.class));
        principal.setValidFrom(claims.getNotBefore());
        principal.setValidTo(claims.getExpiration());
        principal.setSerialNumber(claims.getId());
        principal.setIssuer(claims.getIssuer());
        principal.setAudience(Collections.singletonList(claims.getAudience()));

        TreeSet<String> scopes = extractScopes(claims);
        principal.setRoles(RoleMapper.mapScopesToRoles(scopes));

        return principal;
    }

    private TreeSet<String> extractScopes(Claims claims) {
        TreeSet<String> scopes = new TreeSet<>();
        String scopeString = claims.get("scope", String.class);
        if (!StringUtils.isEmpty(scopeString)) {
            Collections.addAll(scopes, scopeString.split(" "));
        }
        return scopes;
    }
}
