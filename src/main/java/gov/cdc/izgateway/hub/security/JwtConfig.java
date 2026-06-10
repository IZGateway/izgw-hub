package gov.cdc.izgateway.hub.security;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {

    private String issuer;
    private String secretsManagerSecretName;
    private Duration secretCacheTtl = Duration.ofHours(1);
    private Duration credentialCacheTtl = Duration.ofMinutes(5);
    /** Local dev only — bypasses Secrets Manager. Must not be set in non-local profiles. */
    private String testSecret;

    /**
     * Provide a SecretsManagerClient bean when jwt.test-secret is not set.
     * When test-secret is present, SM is not needed and this bean is skipped to
     * avoid credential lookup failures in local environments.
     */
    @Bean
    @ConditionalOnProperty(name = "jwt.test-secret", havingValue = "", matchIfMissing = true)
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.create();
    }
}
