package gov.cdc.izgateway.dynamodb.repository;

import gov.cdc.izgateway.dynamodb.model.ApiKeyCredential;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the grace-revocation selection predicate
 * ({@link ApiKeyCredentialRepository#selectGraceCandidates}). The DynamoDB query itself
 * ({@code findByType}) is a thin base-class delegation and is covered by integration testing;
 * these tests pin the selection logic deterministically.
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
}
