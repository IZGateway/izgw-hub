package gov.cdc.izgateway.principal.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtPrincipalProviderConfig {
    // @Value("${jwt.provider}:client-credentials")
    private String jwtProvider = "shared-secret";

    @Bean
    public JwtPrincipalProvider jwtPrincipalProvider(
            JwtClientCredentialsPrincipalProvider clientCredentialsProvider,
            JwtSharedSecretPrincipalProvider sharedSecretProvider) {
        if ("client-credentials".equalsIgnoreCase(jwtProvider)) {
            return clientCredentialsProvider;
        } else if ("shared-secret".equalsIgnoreCase(jwtProvider)) {
            return sharedSecretProvider;
        } else {
            throw new IllegalArgumentException("Invalid JWT provider specified: " + jwtProvider);
        }
    }
}
