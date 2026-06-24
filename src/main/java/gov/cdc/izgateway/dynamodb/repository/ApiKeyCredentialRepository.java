package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class ApiKeyCredentialRepository extends DynamoDbRepository<ApiKeyCredential> {

    public ApiKeyCredentialRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(ApiKeyCredential.class, client, tableName);
    }

    public Optional<ApiKeyCredential> findByEnvAndJti(String env, String jti) {
        return Optional.ofNullable(find(env + "#" + jti));
    }

    /**
     * Return the credentials in the given environment that are eligible for automated
     * grace-period revocation (IGDD-2711): {@code status == "active"}, a non-null
     * {@code graceExpiresAt}, and {@code graceExpiresAt <= now}.
     *
     * <p><b>NOT YET IMPLEMENTED — returns an empty list.</b> The selection requires the
     * {@code graceExpiresAt} attribute on {@link ApiKeyCredential}, which is written by
     * Config Console's renewal route (IGDD-2707) and must be added to the Hub entity first
     * (change tasks 0.1 and 1.1). Once the field exists, the intended implementation is an
     * environment-scoped sort-key prefix query followed by an in-memory filter, e.g.:
     *
     * <pre>{@code
     * Instant now = Instant.now();
     * return findByType(env + "#").stream()
     *         .filter(c -> "active".equals(c.getStatus()))
     *         .filter(c -> c.getGraceExpiresAt() != null)
     *         .filter(c -> !c.getGraceExpiresAt().isAfter(now))
     *         .toList();
     * }</pre>
     *
     * Returning an empty list keeps the grace-period revocation job inert until the contract
     * with IGDD-2707 is confirmed, so it cannot revoke anything based on an unverified schema.
     *
     * @param env the environment to scope the query to (sort-key prefix {@code {env}#})
     * @return the credentials eligible for grace-period revocation; currently always empty
     */
    public List<ApiKeyCredential> findGraceRevocationCandidates(String env) {
        // TODO(IGDD-2711, tasks 0.1/1.1): implement once ApiKeyCredential.graceExpiresAt exists.
        return Collections.emptyList();
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
