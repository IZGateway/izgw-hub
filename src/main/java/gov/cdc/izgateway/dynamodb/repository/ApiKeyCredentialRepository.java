package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.time.Instant;
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
     * Return the credentials in the given environment that are eligible for automated grace-period
     * revocation (IGDD-2711): {@code status == "active"}, a non-null {@code graceExpiresAt}, and
     * {@code graceExpiresAt <= now}.
     *
     * <p>A superseded key stays {@code active} during its grace window with a non-null
     * {@code graceExpiresAt} set by Config Console at renewal (IGDD-2707); there is no distinct grace
     * status. A never-renewed active key has {@code graceExpiresAt == null} and is excluded. The query
     * is environment-scoped via the {@code {env}#} sort-key prefix, then filtered in memory (see design
     * D4 — a periodic prefix query is adequate at expected key volumes; a GSI is the future escape hatch).</p>
     *
     * @param env the environment to scope the query to (sort-key prefix {@code {env}#})
     * @return the credentials whose grace period has expired; never {@code null}
     */
    public List<ApiKeyCredential> findGraceRevocationCandidates(String env) {
        Instant now = Instant.now();
        return findByType(env + "#").stream()
                .filter(c -> "active".equals(c.getStatus()))
                .filter(c -> c.getGraceExpiresAt() != null)
                .filter(c -> !c.getGraceExpiresAt().isAfter(now))
                .toList();
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
