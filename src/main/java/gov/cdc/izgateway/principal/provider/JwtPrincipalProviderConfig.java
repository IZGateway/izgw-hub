package gov.cdc.izgateway.principal.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtPrincipalProviderConfig {
    @Value("${jwt.provider:shared-secret}")
    private String jwtProvider;

    @Bean
    public JwtPrincipalProvider jwtPrincipalProvider(
            JwtJwksPrincipalProvider clientCredentialsProvider,
            JwtSharedSecretPrincipalProvider sharedSecretProvider) {
        if ("jwks".equalsIgnoreCase(jwtProvider)) {
            return clientCredentialsProvider;
        } else if ("shared-secret".equalsIgnoreCase(jwtProvider)) {
            return sharedSecretProvider;
        } else {
            throw new IllegalArgumentException("Invalid JWT provider specified: " + jwtProvider);
        }
    }
}
