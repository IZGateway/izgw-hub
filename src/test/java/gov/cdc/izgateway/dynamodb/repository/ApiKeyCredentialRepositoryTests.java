package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the grace-revocation selection predicate
 * ({@link ApiKeyCredentialRepository#selectGraceCandidates}), the terminal-status resolver
 * ({@link ApiKeyCredentialRepository#resolveTerminalStatus}), the conditional termination-request
 * builder ({@link ApiKeyCredentialRepository#buildGraceTerminationRequest}), and the per-item
 * malformed-timestamp sanitizer ({@link ApiKeyCredentialRepository#sanitizeAndMap}, IGDD-3344). The
 * DynamoDB calls themselves are thin delegations covered by integration testing; these tests pin the
 * pure logic.
 */
class ApiKeyCredentialRepositoryTests {

    private static final Instant NOW = Instant.parse("2026-06-29T12:00:00Z");

    private ApiKeyCredential cred(String status, Instant graceExpiresAt) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setStatus(status);
        c.setGraceExpiresAt(graceExpiresAt);
        return c;
    }

    @Test
    void includesGracePeriodWithPastGraceExpiry() {
        ApiKeyCredential c = cred("grace_period", NOW.minus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).containsExactly(c);
    }

    @Test
    void includesGracePeriodWithGraceExpiryExactlyNow() {
        ApiKeyCredential c = cred("grace_period", NOW);
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).containsExactly(c);
    }

    @Test
    void excludesGracePeriodWithFutureGraceExpiry() {
        ApiKeyCredential c = cred("grace_period", NOW.plus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).isEmpty();
    }

    @Test
    void excludesGracePeriodWithoutGraceExpiry() {
        ApiKeyCredential c = cred("grace_period", null);
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).isEmpty();
    }

    @Test
    void excludesActiveEvenWithPastGraceExpiry() {
        // A normal active key is never a revocation candidate — only grace_period keys are.
        ApiKeyCredential active = cred("active", NOW.minus(1, ChronoUnit.HOURS));
        ApiKeyCredential revoked = cred("revoked", NOW.minus(1, ChronoUnit.HOURS));
        ApiKeyCredential expired = cred("expired", NOW.minus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(active, revoked, expired), NOW)).isEmpty();
    }

    @Test
    void selectsOnlyEligibleFromMixedList() {
        ApiKeyCredential eligible1 = cred("grace_period", NOW.minus(10, ChronoUnit.MINUTES));
        ApiKeyCredential future = cred("grace_period", NOW.plus(10, ChronoUnit.MINUTES));
        ApiKeyCredential noGrace = cred("grace_period", null);
        ApiKeyCredential activeKey = cred("active", NOW.minus(10, ChronoUnit.MINUTES));
        ApiKeyCredential revoked = cred("revoked", NOW.minus(10, ChronoUnit.MINUTES));
        ApiKeyCredential eligible2 = cred("grace_period", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(
                List.of(eligible1, future, noGrace, activeKey, revoked, eligible2), NOW))
                .containsExactly(eligible1, eligible2);
    }

    @Test
    void buildGraceTerminationRequest_revokedBranch_targetsCorrectKeyWithGracePeriodCondition() {
        Instant terminatedAt = Instant.parse("2026-07-20T15:00:00Z");
        UpdateItemRequest req = ApiKeyCredentialRepository.buildGraceTerminationRequest(
                "izgateway-dev-test", "jti-abc", "revoked", terminatedAt, "system:grace-revocation");

        assertThat(req.tableName()).isEqualTo("izgateway-dev-test");
        // Targets the exact item: partition entityType=ApiKeyCredential, sort key {jti} (no env prefix).
        assertThat(req.key().get("entityType").s()).isEqualTo("ApiKeyCredential");
        assertThat(req.key().get("sortKey").s()).isEqualTo("jti-abc");
        // Only writes when still grace_period → exactly-once revoke across concurrent instances.
        assertThat(req.conditionExpression()).isEqualTo("#st = :grace");
        assertThat(req.expressionAttributeNames()).containsEntry("#st", "status");
        assertThat(req.expressionAttributeValues().get(":grace").s()).isEqualTo("grace_period");
        assertThat(req.expressionAttributeValues().get(":terminal").s()).isEqualTo("revoked");
        // Writes revokedAt/revokedBy for the revoked branch; expiredAt/expiredBy stay untouched.
        assertThat(req.updateExpression()).contains("revokedAt = :ta", "revokedBy = :tb")
                .doesNotContain("expiredAt", "expiredBy");
        // terminatedAt is the ISO-8601 'Z' Instant form; updatedOn uses the DynamoDbAudit Date form
        // (millis + numeric +0000 offset) so it round-trips through the Enhanced Client's converter.
        assertThat(req.expressionAttributeValues().get(":ta").s()).isEqualTo("2026-07-20T15:00:00Z");
        assertThat(req.expressionAttributeValues().get(":tb").s()).isEqualTo("system:grace-revocation");
        assertThat(req.updateExpression()).contains("updatedOn = :uo", "updatedBy = :tb");
        assertThat(req.expressionAttributeValues().get(":uo").s()).isEqualTo("2026-07-20T15:00:00.000+0000");
    }

    @Test
    void buildGraceTerminationRequest_expiredBranch_writesExpiredFieldsOnly() {
        Instant terminatedAt = Instant.parse("2026-07-20T15:00:00Z");
        UpdateItemRequest req = ApiKeyCredentialRepository.buildGraceTerminationRequest(
                "izgateway-dev-test", "jti-abc", "expired", terminatedAt, "system:grace-expiration");

        assertThat(req.expressionAttributeValues().get(":terminal").s()).isEqualTo("expired");
        assertThat(req.updateExpression()).contains("expiredAt = :ta", "expiredBy = :tb")
                .doesNotContain("revokedAt", "revokedBy");
        assertThat(req.expressionAttributeValues().get(":tb").s()).isEqualTo("system:grace-expiration");
    }

    private ApiKeyCredential credWithExpiry(Instant expiresAt, Instant graceExpiresAt) {
        ApiKeyCredential c = new ApiKeyCredential();
        c.setExpiresAt(expiresAt);
        c.setGraceExpiresAt(graceExpiresAt);
        return c;
    }

    @Test
    void resolveTerminalStatus_expiresBeforeGraceExpiry_isExpired() {
        ApiKeyCredential c = credWithExpiry(NOW.minus(1, ChronoUnit.HOURS), NOW);
        assertThat(ApiKeyCredentialRepository.resolveTerminalStatus(c)).isEqualTo("expired");
    }

    @Test
    void resolveTerminalStatus_expiresEqualsGraceExpiry_isExpired() {
        ApiKeyCredential c = credWithExpiry(NOW, NOW);
        assertThat(ApiKeyCredentialRepository.resolveTerminalStatus(c)).isEqualTo("expired");
    }

    @Test
    void resolveTerminalStatus_graceExpiryBeforeExpires_isRevoked() {
        ApiKeyCredential c = credWithExpiry(NOW.plus(1, ChronoUnit.HOURS), NOW);
        assertThat(ApiKeyCredentialRepository.resolveTerminalStatus(c)).isEqualTo("revoked");
    }

    @Test
    void resolveTerminalStatus_nullExpiresAt_defaultsToRevoked() {
        ApiKeyCredential c = credWithExpiry(null, NOW);
        assertThat(ApiKeyCredentialRepository.resolveTerminalStatus(c)).isEqualTo("revoked");
    }

    private Map<String, AttributeValue> rawItem(String jti, String status, String graceExpiresAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("entityType", AttributeValue.fromS("ApiKeyCredential"));
        // "sortKey" is the derived DynamoDB key attribute (has a phantom no-op setter); "jti" is the
        // real bean property. Both are present on an actual row.
        item.put("sortKey", AttributeValue.fromS(jti));
        item.put("jti", AttributeValue.fromS(jti));
        item.put("status", AttributeValue.fromS(status));
        item.put("issuedAt", AttributeValue.fromS("2026-01-01T00:00:00Z"));
        item.put("expiresAt", AttributeValue.fromS("2026-12-31T00:00:00Z"));
        if (graceExpiresAt != null) {
            item.put("graceExpiresAt", AttributeValue.fromS(graceExpiresAt));
        }
        return item;
    }

    @Test
    void sanitizeAndMap_allValidTimestamps_mapsCleanly() {
        Map<String, AttributeValue> item = rawItem("jti-good", "grace_period", "2026-07-20T00:00:00Z");

        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(c.getJti()).isEqualTo("jti-good");
        assertThat(c.getStatus()).isEqualTo("grace_period");
        assertThat(c.getIssuedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(c.getExpiresAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
        assertThat(c.getGraceExpiresAt()).isEqualTo(Instant.parse("2026-07-20T00:00:00Z"));
    }

    @Test
    void sanitizeAndMap_malformedGraceExpiresAt_nullsOnlyThatFieldAndKeepsRestOfRecord() {
        // Reproduces the reported bad legacy value: missing colon in the UTC offset (+0000 vs +00:00/Z).
        Map<String, AttributeValue> item = rawItem("jti-bad-grace", "grace_period", "2025-06-23T13:28:00.000+0000");

        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(c.getJti()).isEqualTo("jti-bad-grace");
        assertThat(c.getStatus()).isEqualTo("grace_period");
        assertThat(c.getGraceExpiresAt()).isNull();
        // Unrelated valid Instant fields on the same record are unaffected.
        assertThat(c.getIssuedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(c.getExpiresAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
    }

    @Test
    void sanitizeAndMap_malformedGraceExpiresAt_dropsOutOfGraceCandidateSelection() {
        // End-to-end of the fix's intent: a record that would previously abort findAll() entirely now
        // maps to a credential that selectGraceCandidates naturally excludes (graceExpiresAt == null),
        // instead of the malformed value silently making it look like a non-expiring grace_period key.
        Map<String, AttributeValue> item = rawItem("jti-bad-grace", "grace_period", "2025-06-23T13:28:00.000+0000");
        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).isEmpty();
    }

    @Test
    void sanitizeAndMap_multipleMalformedFields_nullsEachIndependently() {
        Map<String, AttributeValue> item = rawItem("jti-multi-bad", "grace_period", "2025-06-23T13:28:00.000+0000");
        item.put("issuedAt", AttributeValue.fromS("not-a-timestamp"));

        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(c.getGraceExpiresAt()).isNull();
        assertThat(c.getIssuedAt()).isNull();
        // A field that parses fine is untouched even though sibling fields on the same item are bad.
        assertThat(c.getExpiresAt()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
    }

    @Test
    void sanitizeAndMap_unmappableAttributeOutsideInstantSet_skipsRecordInsteadOfThrowing() {
        // A bad-data case the up-front Instant validation doesn't cover (e.g. a corrupt Set attribute)
        // must not propagate out of sanitizeAndMap -- that would still let one bad row abort findAll().
        Map<String, AttributeValue> item = rawItem("jti-corrupt", "grace_period", "2026-07-20T00:00:00Z");
        item.put("environments", AttributeValue.fromS("not-a-number-set"));

        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(c).isNull();
    }

    @Test
    void sanitizeAndMap_noGraceExpiresAtAttribute_mapsWithNullField() {
        // Normal active keys have no graceExpiresAt attribute at all (absent, not malformed).
        Map<String, AttributeValue> item = rawItem("jti-active", "active", null);

        ApiKeyCredential c = ApiKeyCredentialRepository.sanitizeAndMap(item);

        assertThat(c.getGraceExpiresAt()).isNull();
        assertThat(c.getStatus()).isEqualTo("active");
    }
}
