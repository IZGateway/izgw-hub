package gov.cdc.izgateway.hub.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for the grace-period revocation scheduled job (IGDD-2711).
 *
 * <p>Bound from properties under the {@code apikey.grace-revocation} prefix. The job is
 * <b>disabled by default</b> ({@link #enabled} = {@code false}) so it does not run until a
 * deployment explicitly opts in per environment — and so it stays inert while its DynamoDB
 * contract with Config Console (IGDD-2707) is still being confirmed.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "apikey.grace-revocation")
@Data
public class GracePeriodRevocationProperties {

    /** Whether the grace-period revocation job is enabled. Disabled by default. */
    private boolean enabled = false;

    /** How often the job runs (fixed delay between completions). Defaults to one hour. */
    private Duration interval = Duration.ofHours(1);

    /** Delay before the first run after startup, allowing the application to finish initializing. */
    private Duration initialDelay = Duration.ofMinutes(5);
}
