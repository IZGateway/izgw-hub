package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class ApiKeyCredentialRepository extends DynamoDbRepository<ApiKeyCredential> {

    /** Status of a renewed (superseded) credential during its grace window; still authenticates. */
    public static final String STATUS_GRACE_PERIOD = "grace_period";

    /** Terminal status set when a credential is revoked. */
    public static final String STATUS_REVOKED = "revoked";

    public ApiKeyCredentialRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(ApiKeyCredential.class, client, tableName);
    }

    public Optional<ApiKeyCredential> findByEnvAndJti(String env, String jti) {
        return Optional.ofNullable(find(env + "#" + jti));
    }

    /**
     * Return the credentials in the given environment eligible for automated grace-period revocation
     * (IGDD-2711): {@code status == "grace_period"}, a non-null {@code graceExpiresAt}, and
     * {@code graceExpiresAt <= now}.
     *
     * <p>On renewal, Config Console moves the superseded (old) key to {@code grace_period} with a
     * {@code graceExpiresAt} (IGDD-2707); it keeps authenticating alongside the new key until that
     * instant passes, at which point this job revokes it. Normal {@code active} keys have no
     * {@code graceExpiresAt} and are never selected. The query is environment-scoped via the
     * {@code {env}#} sort-key prefix, then filtered in memory (design D4).</p>
     *
     * @param env the environment to scope the query to (sort-key prefix {@code {env}#})
     * @return the grace-period credentials whose grace period has expired; never {@code null}
     */
    public List<ApiKeyCredential> findGraceRevocationCandidates(String env) {
        return selectGraceCandidates(findByType(env + "#"), Instant.now());
    }

    /**
     * Pure selection predicate, extracted for testability: from {@code candidates}, return those in
     * {@code grace_period} status with a non-null {@code graceExpiresAt} at or before {@code now}.
     * (A grace expiry exactly equal to {@code now} is included.)
     *
     * @param candidates the records to filter (e.g. all credentials in an environment)
     * @param now        the reference instant to compare {@code graceExpiresAt} against
     * @return the subset eligible for grace-period revocation
     */
    static List<ApiKeyCredential> selectGraceCandidates(Collection<ApiKeyCredential> candidates, Instant now) {
        return candidates.stream()
                .filter(c -> STATUS_GRACE_PERIOD.equals(c.getStatus()))
                .filter(c -> c.getGraceExpiresAt() != null)
                .filter(c -> !c.getGraceExpiresAt().isAfter(now))
                .toList();
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
