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
    void includesActiveWithPastGraceExpiry() {
        ApiKeyCredential c = cred("active", NOW.minus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).containsExactly(c);
    }

    @Test
    void includesActiveWithGraceExpiryExactlyNow() {
        ApiKeyCredential c = cred("active", NOW);
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).containsExactly(c);
    }

    @Test
    void excludesActiveWithFutureGraceExpiry() {
        ApiKeyCredential c = cred("active", NOW.plus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).isEmpty();
    }

    @Test
    void excludesActiveWithoutGraceExpiry() {
        ApiKeyCredential c = cred("active", null);
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(c), NOW)).isEmpty();
    }

    @Test
    void excludesNonActiveEvenWithPastGraceExpiry() {
        ApiKeyCredential revoked = cred("revoked", NOW.minus(1, ChronoUnit.HOURS));
        ApiKeyCredential expired = cred("expired", NOW.minus(1, ChronoUnit.HOURS));
        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(List.of(revoked, expired), NOW)).isEmpty();
    }

    @Test
    void selectsOnlyEligibleFromMixedList() {
        ApiKeyCredential eligible1 = cred("active", NOW.minus(10, ChronoUnit.MINUTES));
        ApiKeyCredential future = cred("active", NOW.plus(10, ChronoUnit.MINUTES));
        ApiKeyCredential noGrace = cred("active", null);
        ApiKeyCredential revoked = cred("revoked", NOW.minus(10, ChronoUnit.MINUTES));
        ApiKeyCredential eligible2 = cred("active", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(ApiKeyCredentialRepository.selectGraceCandidates(
                List.of(eligible1, future, noGrace, revoked, eligible2), NOW))
                .containsExactly(eligible1, eligible2);
    }
}
