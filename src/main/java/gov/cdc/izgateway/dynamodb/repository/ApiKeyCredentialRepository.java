package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ApiKeyCredentialRepository extends DynamoDbRepository<ApiKeyCredential> {

    /** Status of a renewed (superseded) credential during its grace window; still authenticates. */
    public static final String STATUS_GRACE_PERIOD = "grace_period";

    /** Terminal status set when a credential is revoked. */
    public static final String STATUS_REVOKED = "revoked";

    /** DynamoDB partition-key value (entity discriminator) for ApiKeyCredential rows. */
    private static final String ENTITY_TYPE = "ApiKeyCredential";

    private final DynamoDbClient ddbClient;
    private final String tableName;

    public ApiKeyCredentialRepository(@Autowired DynamoDbEnhancedClient client,
                                      @Autowired DynamoDbClient ddbClient,
                                      String tableName) {
        super(ApiKeyCredential.class, client, tableName);
        this.ddbClient = ddbClient;
        this.tableName = tableName;
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

    /**
     * Atomically revoke a credential only if it is still in {@code grace_period}, so that when
     * multiple Hub instances run the sweep concurrently a given key is revoked — and therefore
     * audited — exactly once. Uses a conditional DynamoDB update (condition: {@code status =
     * "grace_period"}); the losing instances get a condition failure and do nothing.
     *
     * @param credential the grace-period candidate to revoke
     * @param revokedAt  the revocation timestamp
     * @param revokedBy  the revoking actor (e.g. {@code system:grace-revocation})
     * @return {@code true} if this call performed the revocation; {@code false} if the condition
     *         failed (another instance already revoked it, or its status is no longer grace_period)
     */
    public boolean revokeIfGracePeriod(ApiKeyCredential credential, Instant revokedAt, String revokedBy) {
        try {
            ddbClient.updateItem(buildGraceRevokeRequest(
                    tableName, credential.getEnv(), credential.getJti(), revokedAt, revokedBy));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * Build the conditional {@code UpdateItem} that flips a credential from {@code grace_period} to
     * {@code revoked}. Package-private and static so the request shape can be unit-tested without
     * DynamoDB. ({@code status} is a DynamoDB reserved word, hence the {@code #st} name placeholder.)
     */
    static UpdateItemRequest buildGraceRevokeRequest(String tableName, String env, String jti,
                                                     Instant revokedAt, String revokedBy) {
        return UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "entityType", AttributeValue.fromS(ENTITY_TYPE),
                        "sortKey", AttributeValue.fromS(env + "#" + jti)))
                .updateExpression("SET #st = :revoked, revokedAt = :ra, revokedBy = :rb")
                .conditionExpression("#st = :grace")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":revoked", AttributeValue.fromS(STATUS_REVOKED),
                        ":grace", AttributeValue.fromS(STATUS_GRACE_PERIOD),
                        ":ra", AttributeValue.fromS(revokedAt.toString()),
                        ":rb", AttributeValue.fromS(revokedBy)))
                .build();
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
