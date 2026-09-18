package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class ApiKeyCredentialRepository extends DynamoDbRepository<ApiKeyCredential> {

    /** Status of a renewed (superseded) credential during its grace window; still authenticates. */
    public static final String STATUS_GRACE_PERIOD = "grace_period";

    /** Terminal status set when a credential is cut off before reaching its own expiry. */
    public static final String STATUS_REVOKED = "revoked";

    /** Terminal status set when a credential reached its own expiry (JWT {@code exp}). */
    public static final String STATUS_EXPIRED = "expired";

    /** DynamoDB partition-key value (entity discriminator) for ApiKeyCredential rows. */
    private static final String ENTITY_TYPE = "ApiKeyCredential";

    /**
     * Attribute names of {@link ApiKeyCredential}'s {@code Instant} fields -- serialized as ISO-8601
     * strings by the Enhanced Client's default {@code InstantAsStringAttributeConverter}, which throws
     * on a malformed value instead of degrading gracefully. Validated up front in {@link #findAll()} so
     * one bad legacy value can't abort the scan (IGDD-3344).
     */
    private static final List<String> INSTANT_ATTRIBUTES =
            List.of("issuedAt", "expiresAt", "revokedAt", "expiredAt", "graceExpiresAt");

    /** Bean schema used to map a sanitized raw item to an {@link ApiKeyCredential} in {@link #findAll()}. */
    private static final TableSchema<ApiKeyCredential> SCHEMA = TableSchema.fromBean(ApiKeyCredential.class);

    /**
     * Format for the inherited {@code DynamoDbAudit} timestamps (e.g. {@code updatedOn}) — matches the
     * Enhanced Client's date converter (millisecond precision, numeric UTC offset like {@code +0000}),
     * distinct from the ISO-8601 {@code Z} form used by {@code Instant} fields such as {@code revokedAt}.
     */
    private static final DateTimeFormatter AUDIT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx").withZone(ZoneOffset.UTC);

    private final DynamoDbClient ddbClient;
    private final String tableName;

    // Not a Spring @Component: the DynamoDbRepositoryFactory constructs this directly, so the
    // parameters are intentionally not @Autowired (Spring never invokes this constructor).
    public ApiKeyCredentialRepository(DynamoDbEnhancedClient client, DynamoDbClient ddbClient, String tableName) {
        super(ApiKeyCredential.class, client, tableName);
        this.ddbClient = ddbClient;
        this.tableName = tableName;
    }

    public Optional<ApiKeyCredential> findByJti(String jti) {
        return Optional.ofNullable(find(jti));
    }

    /**
     * Return the credentials eligible for automated grace-period revocation (IGDD-2711):
     * {@code status == "grace_period"}, a non-null {@code graceExpiresAt}, and
     * {@code graceExpiresAt <= now}.
     *
     * <p>On renewal, Config Console moves the superseded (old) key to {@code grace_period} with a
     * {@code graceExpiresAt} (IGDD-2707); it keeps authenticating alongside the new key until that
     * instant passes, at which point this job revokes it. Normal {@code active} keys have no
     * {@code graceExpiresAt} and are never selected. Because the sort key is {@code {jti}} with no
     * environment prefix (IGDD-3140), the sweep is environment-agnostic: it scans every
     * {@code ApiKeyCredential} record (by {@code entityType}) and filters in memory (design D4).</p>
     *
     * @return the grace-period credentials whose grace period has expired; never {@code null}
     */
    public List<ApiKeyCredential> findGraceRevocationCandidates() {
        return selectGraceCandidates(findAll(), Instant.now());
    }

    /**
     * Resilient override of {@link DynamoDbRepository#findAll()} (IGDD-3344): the inherited
     * implementation maps every item through the Enhanced Client's bean mapper in one pass, so a single
     * row with a malformed {@code Instant} attribute (e.g. a non-ISO-8601 legacy value) throws out of
     * the whole scan before a single candidate is evaluated -- which silently wedges the grace-period
     * sweep for every environment sharing the table, since {@link #findGraceRevocationCandidates()} is
     * table-wide by design (D4).
     *
     * <p>Instead, this queries raw items directly via the low-level client, validates each item's
     * {@code Instant} attributes up front, and nulls out (rather than propagating) any that fail to
     * parse -- mirroring {@code DateConverter}'s "log it, return null, keep going" precedent for the
     * equivalent {@code Date} case. A credential with an unparseable {@code graceExpiresAt} then simply
     * falls out of {@link #selectGraceCandidates}'s {@code graceExpiresAt != null} filter instead of
     * blocking every other credential's evaluation.</p>
     *
     * @return every {@code ApiKeyCredential} row, with any malformed Instant attribute set to null
     */
    @Override
    public List<ApiKeyCredential> findAll() {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("entityType = :et")
                .expressionAttributeValues(Map.of(":et", AttributeValue.fromS(ENTITY_TYPE)))
                .build();

        List<ApiKeyCredential> result = new ArrayList<>();
        for (QueryResponse page : ddbClient.queryPaginator(request)) {
            for (Map<String, AttributeValue> item : page.items()) {
                ApiKeyCredential credential = sanitizeAndMap(item);
                if (credential != null) {
                    result.add(credential);
                }
            }
        }
        return result;
    }

    /**
     * Validate a raw item's {@code Instant} attributes and map it to an {@link ApiKeyCredential},
     * nulling out (and logging) any attribute that fails to parse rather than letting the bean mapper
     * throw. Package-private and static, given a raw item, for testability without DynamoDB.
     *
     * <p>The log line is deliberately structured ({@code eventType: CREDENTIAL_TIMESTAMP_PARSE_FAILED})
     * and carries the entity type, {@code jti}, offending attribute name, and raw unparseable value, so
     * it can be matched by an Elastic watcher/alert -- and fires every time a malformed value is seen,
     * not just once per record, so repeated occurrences show as sustained alert pressure rather than a
     * one-time blip (per review comment on IGDD-3344).</p>
     *
     * <p>The Instant attributes above are the only known bad-data case, but the backstop {@code catch}
     * around the mapping call itself means a failure on <em>any other</em> attribute is skipped -- not
     * just logged and re-thrown -- so this record (and only this record) is dropped from the result
     * rather than aborting {@link #findAll()} for every other row.</p>
     *
     * @param item the raw attribute map for one {@code ApiKeyCredential} row
     * @return the mapped credential, with any malformed Instant attribute set to null; {@code null} if
     *         the item could not be mapped at all
     */
    static ApiKeyCredential sanitizeAndMap(Map<String, AttributeValue> item) {
        String jti = item.containsKey("sortKey") ? item.get("sortKey").s() : null;
        Map<String, AttributeValue> sanitized = null;

        for (String attribute : INSTANT_ATTRIBUTES) {
            AttributeValue value = item.get(attribute);
            if (value == null || value.s() == null) {
                continue;
            }
            try {
                Instant.parse(value.s());
            } catch (DateTimeParseException e) {
                log.error(Markers2.append(
                                "eventType", "CREDENTIAL_TIMESTAMP_PARSE_FAILED",
                                "entityType", ENTITY_TYPE,
                                "keyId", jti,
                                "attribute", attribute,
                                "rawValue", value.s())
                        .and(Markers2.append(e)),
                        "Skipping unparseable {} '{}' on ApiKeyCredential {}", attribute, value.s(), jti);
                if (sanitized == null) {
                    sanitized = new HashMap<>(item);
                }
                sanitized.remove(attribute);
            }
        }

        try {
            return SCHEMA.mapToItem(sanitized != null ? sanitized : item);
        } catch (RuntimeException e) {  // NOSONAR — one unmappable row must not abort the rest of the scan
            log.error(Markers2.append(
                            "eventType", "CREDENTIAL_TIMESTAMP_PARSE_FAILED",
                            "entityType", ENTITY_TYPE,
                            "keyId", jti)
                    .and(Markers2.append(e)),
                    "Skipping unmappable ApiKeyCredential {}: {}", jti, e.getMessage());
            return null;
        }
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
     * Resolve the terminal status a grace-expired credential should transition to (IGDD-3167). Per
     * the credential state model, the JWT {@code exp} caps validity, so the effective grace end is
     * {@code min(graceExpiresAt, expiresAt)}:
     * <ul>
     *   <li>{@link #STATUS_EXPIRED} if the credential reached its own {@code expiresAt} on or before
     *       {@code graceExpiresAt} ended — the key's own lifetime capped it first.</li>
     *   <li>{@link #STATUS_REVOKED} otherwise — the grace window was cut off before the key's own
     *       expiry (including the defensive case of a missing {@code expiresAt}).</li>
     * </ul>
     * Pure function of the candidate's own fields, so callers may resolve the same label
     * independently (e.g. to pick an audit event) without re-reading the record.
     *
     * @param credential the grace-period candidate being terminated
     * @return {@link #STATUS_EXPIRED} or {@link #STATUS_REVOKED}
     */
    public static String resolveTerminalStatus(ApiKeyCredential credential) {
        Instant expiresAt = credential.getExpiresAt();
        Instant graceExpiresAt = credential.getGraceExpiresAt();
        if (expiresAt != null && graceExpiresAt != null && !expiresAt.isAfter(graceExpiresAt)) {
            return STATUS_EXPIRED;
        }
        return STATUS_REVOKED;
    }

    /**
     * Atomically terminate a credential only if it is still in {@code grace_period}, so that when
     * multiple Hub instances run the sweep concurrently a given key is terminated — and therefore
     * audited — exactly once. Uses a conditional DynamoDB update (condition: {@code status =
     * "grace_period"}); the losing instances get a condition failure and do nothing. The terminal
     * status ({@code expired} vs {@code revoked}) is resolved from the credential's own fields via
     * {@link #resolveTerminalStatus} (IGDD-3167).
     *
     * @param credential   the grace-period candidate to terminate
     * @param terminatedAt the termination timestamp
     * @param terminatedBy the terminating actor (e.g. {@code system:grace-expiration} or
     *                     {@code system:grace-revocation})
     * @return {@code true} if this call performed the termination; {@code false} if the condition
     *         failed (another instance already terminated it, or its status is no longer grace_period)
     */
    public boolean terminateIfGracePeriod(ApiKeyCredential credential, Instant terminatedAt, String terminatedBy) {
        String terminalStatus = resolveTerminalStatus(credential);
        try {
            ddbClient.updateItem(buildGraceTerminationRequest(
                    tableName, credential.getJti(), terminalStatus, terminatedAt, terminatedBy));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * Build the conditional {@code UpdateItem} that flips a credential from {@code grace_period} to
     * its resolved terminal status ({@code expired} or {@code revoked}), writing the matching
     * timestamp/actor attribute pair ({@code expiredAt}/{@code expiredBy} or
     * {@code revokedAt}/{@code revokedBy}) so the other pair stays {@code null}. Package-private and
     * static so the request shape can be unit-tested without DynamoDB. ({@code status} is a DynamoDB
     * reserved word, hence the {@code #st} name placeholder.)
     */
    static UpdateItemRequest buildGraceTerminationRequest(String tableName, String jti,
            String terminalStatus, Instant terminatedAt, String terminatedBy) {
        boolean expired = STATUS_EXPIRED.equals(terminalStatus);
        String timestampAttr = expired ? "expiredAt" : "revokedAt";
        String actorAttr = expired ? "expiredBy" : "revokedBy";
        return UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "entityType", AttributeValue.fromS(ENTITY_TYPE),
                        // Sort key is the jti alone — no environment prefix (IGDD-3140).
                        "sortKey", AttributeValue.fromS(jti)))
                // Also bump the inherited audit fields (updatedOn/updatedBy) the way saveAndFlush would,
                // so tooling/queries keyed on updatedOn see the termination instead of the create time.
                .updateExpression("SET #st = :terminal, " + timestampAttr + " = :ta, " + actorAttr + " = :tb, "
                        + "updatedOn = :uo, updatedBy = :tb")
                .conditionExpression("#st = :grace")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":terminal", AttributeValue.fromS(terminalStatus),
                        ":grace", AttributeValue.fromS(STATUS_GRACE_PERIOD),
                        ":ta", AttributeValue.fromS(terminatedAt.toString()),
                        ":tb", AttributeValue.fromS(terminatedBy),
                        ":uo", AttributeValue.fromS(AUDIT_TIMESTAMP.format(terminatedAt))))
                .build();
    }

    @Override
    public ApiKeyCredential store(ApiKeyCredential credential) {
        return saveAndFlush(credential);
    }
}
