package gov.cdc.izgateway.hub.security;

import gov.cdc.izgateway.utils.SystemUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.time.Duration;
import java.util.ServiceConfigurationError;

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

    @PostConstruct
    void validateConfig() {
        if (issuer == null || issuer.isBlank()) {
            throw new ServiceConfigurationError(
                    "jwt.issuer must be configured — set the JWT_ISSUER environment variable");
        }
        if (testSecret != null && !testSecret.isBlank()
                && "Production".equals(SystemUtils.getDestTypeAsString())) {
            throw new ServiceConfigurationError(
                    "jwt.test-secret must not be set in Production — remove JWT_TEST_SECRET from the task definition");
        }
    }

    /**
     * Provide a SecretsManagerClient bean only when jwt.test-secret is absent or blank.
     * When test-secret is present (local dev), SM is not needed; skipping this bean avoids
     * AWS credential resolution failures in local environments.
     *
     * Note: the previous @ConditionalOnProperty(havingValue="") was a no-op — Spring treats
     * an empty havingValue as "match any non-false value", so the bean was always created.
     * @ConditionalOnExpression correctly evaluates the actual property value at refresh time.
     */
    @Bean
    @ConditionalOnExpression("'${jwt.test-secret:}'.isEmpty()")
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.create();
    }
}
