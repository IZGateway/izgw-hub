package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the grace-revocation selection predicate
 * ({@link ApiKeyCredentialRepository#selectGraceCandidates}) and the conditional revoke-request
 * builder ({@link ApiKeyCredentialRepository#buildGraceRevokeRequest}). The DynamoDB calls
 * themselves are thin delegations covered by integration testing; these tests pin the pure logic.
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
    void buildGraceRevokeRequest_targetsCorrectKeyWithGracePeriodCondition() {
        Instant revokedAt = Instant.parse("2026-07-20T15:00:00Z");
        UpdateItemRequest req = ApiKeyCredentialRepository.buildGraceRevokeRequest(
                "izgateway-dev-test", "5", "jti-abc", revokedAt, "system:grace-revocation");

        assertThat(req.tableName()).isEqualTo("izgateway-dev-test");
        // Targets the exact item: partition entityType=ApiKeyCredential, sort key {env}#{jti}.
        assertThat(req.key().get("entityType").s()).isEqualTo("ApiKeyCredential");
        assertThat(req.key().get("sortKey").s()).isEqualTo("5#jti-abc");
        // Only writes when still grace_period → exactly-once revoke across concurrent instances.
        assertThat(req.conditionExpression()).isEqualTo("#st = :grace");
        assertThat(req.expressionAttributeNames()).containsEntry("#st", "status");
        assertThat(req.expressionAttributeValues().get(":grace").s()).isEqualTo("grace_period");
        assertThat(req.expressionAttributeValues().get(":revoked").s()).isEqualTo("revoked");
        // revokedAt is the ISO-8601 'Z' Instant form; updatedOn uses the DynamoDbAudit Date form
        // (millis + numeric +0000 offset) so it round-trips through the Enhanced Client's converter.
        assertThat(req.expressionAttributeValues().get(":ra").s()).isEqualTo("2026-07-20T15:00:00Z");
        assertThat(req.expressionAttributeValues().get(":rb").s()).isEqualTo("system:grace-revocation");
        assertThat(req.updateExpression()).contains("updatedOn = :uo", "updatedBy = :rb");
        assertThat(req.expressionAttributeValues().get(":uo").s()).isEqualTo("2026-07-20T15:00:00.000+0000");
    }
}
