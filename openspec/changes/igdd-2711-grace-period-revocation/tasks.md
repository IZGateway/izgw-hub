## 0. Upstream contract (IGDD-2707) — RESOLVED

- [x] 0.1 Confirm grace-field names/semantics — `graceExpiresAt` (absolute ISO-8601 `Instant`, set on the old key at renewal) and `supersededBy` (`String` jti). Confirmed with the team (2026-06-24/25).
- [x] 0.2 Confirm a superseded key carries `status = "grace_period"` (lowercase) during the grace window — a distinct grace status per D8 / IGDD-2705 commit `82750a99c` (supersedes the earlier "stays `active`, no grace status" position). Hub uses lowercase literals.

## 1. Entity and Repository

- [x] 1.1 Add `graceExpiresAt` (`Instant`, nullable) and `supersededBy` (`String`, nullable) to `ApiKeyCredential` (serialized as ISO-8601 like `issuedAt`/`expiresAt`).
- [x] 1.2 Add `findGraceRevocationCandidates()` to `ApiKeyCredentialRepository` — scan all credential records via `findAll()` (partition = `entityType`; the sort key is `{jti}`, so there is no environment prefix to scope on) + in-memory filter `status == "grace_period" && graceExpiresAt != null && graceExpiresAt <= now`.
- [x] 1.3 Remove the old environment-prefix derivation (`String.valueOf(SystemUtils.getDestType())`) — the `ApiKeyCredential` sort key is `{jti}` alone and a credential's permitted environments are a server-side `environments` list, so the sweep is environment-agnostic and evaluates every credential record.

## 2. Audit event

- [x] 2.1 Add `API_KEY_REVOKED` + `SYSTEM_GRACE_REVOCATION` constants and `apiKeyRevoked(keyId, jurisdictionId, revokedBy, supersededBy)` to `ApiKeyAuditLogger` (no token/secret material).

## 3. Scheduler

- [x] 3.1 Enable Spring scheduling (`@EnableScheduling` on `GracePeriodRevocationScheduler`, gated by `@ConditionalOnProperty`).
- [x] 3.2 Create `GracePeriodRevocationScheduler` — injects repository, audit logger, principal provider; `@Scheduled` `scheduledRun` wraps `runRevocationCycle` with MDC eventId and ERROR-on-failure logging.
- [x] 3.3 Multi-instance safety (design D5). **Revised 2026-07-20:** the host-ordering election was removed (the Elastic host registry retains stale hosts → a lone live instance could defer to a ghost and never run). Replaced with a conditional DynamoDB write (`revokeIfGracePeriod`: revoke only while `status == grace_period`) so each key is revoked+audited exactly once across instances, with no runner election.
- [x] 3.4 Cycle: query candidates → for each, conditionally revoke via `revokeIfGracePeriod` (sets `status=revoked`, `revokedAt`, `revokedBy=system:grace-revocation` iff still grace_period); count only the writes that won.
- [x] 3.5 Emit `API_KEY_REVOKED` per revoked key, passing `supersededBy`.
- [x] 3.6 Cache eviction — **RIGHT-SIZED (out of scope for cross-instance):** the acting instance evicts locally via `evictCredential(jti)`; other instances converge within the credential-cache TTL (≤5 min). Immediate fleet-wide broadcast is intentionally not built — it is the manual-revoke path's concern (IGDD-2707) and the grace ACs don't require it (design D6).
- [x] 3.7 Log per-run counts (`GRACE_REVOCATION_RUN`, evaluated/revoked) at INFO (AC #4).

## 4. Configuration

- [x] 4.1 `GracePeriodRevocationProperties` (`@ConfigurationProperties` `apikey.grace-revocation`): `enabled` (default false), `interval` (1h), `initialDelay` (5m).
- [x] 4.2 `application.yml` stub with `APIKEY_GRACE_REVOCATION_ENABLED` override.
- [x] 4.3 Gate the scheduler on `apikey.grace-revocation.enabled` (`@ConditionalOnProperty`, disabled unless explicitly `true`).

## 5. Monitoring and Runbook (AC #3)

- [x] 5.0 Emit the started/succeeded/failed log trio — `GRACE_REVOCATION_STARTED` (cycle start), `GRACE_REVOCATION_RUN` (success, with evaluated/revoked counts), `GRACE_REVOCATION_FAILED` (error), all structured on `$.eventType`.
- [x] 5.1 Alert setup — **decided (2026-07-01, Paul): log messages only for now; automated alarms deferred.** APHL manages the AWS environment/CloudWatch, so alarms (if added later) are handed to APHL against these log events. Platform chosen for the future = CloudWatch. The alarm spec + a ready Terraform draft are preserved (runbook "Alert conditions" section; the draft `grace_revocation_alarms.tf` was removed from the working tree — regenerate from the spec when revisited).
- [x] 5.2 Operations runbook drafted (`runbook.md`) — log signals, (future) alert conditions, and manual remediation via Config Console `DELETE /api/apikeys/:jti`. Relocate to canonical ops-docs location when finalized.

## 6. Tests

- [x] 6.1 Repository logic — `selectGraceCandidates` (6 cases: past/now/future/null grace, non-grace, mixed) + `buildGraceRevokeRequest` (asserts the conditional UpdateItem: key `{jti}`, condition `status = grace_period`, revoked/timestamp/actor values) unit-tested in `ApiKeyCredentialRepositoryTests`. The DynamoDB calls (`findByType`, `updateItem`) are thin delegations covered by integration/dev testing.
- [ ] 6.2 Entity round-trip — `graceExpiresAt`/`supersededBy` serialize/deserialize without precision loss; legacy null record. **Deferred to integration** — genuinely needs a real DynamoDB table (no DynamoDB Local/Testcontainers harness in this repo); will be covered by the IGDD-2707 end-to-end validation.
- [x] 6.3 Scheduler — winning conditional write → audit emitted with `supersededBy` + local cache evicted (`wonConditionalWrite_isAuditedAndEvicted`); losing write (another instance won) → no audit/evict (`lostConditionalWrite_noAuditNoEvict`); mixed batch audits only the winners.
- [x] 6.5 Scheduler — idempotent skip of already-revoked. (`nonActiveCandidate_isSkipped`, `multipleCandidates_revokesOnlyActiveOnes`)
- [x] 6.6 Scheduler — per-run counts logged.
- [x] 6.7 `API_KEY_REVOKED` field correctness — verified via the scheduler's `verify(auditLogger).apiKeyRevoked(...)` interaction (matches the 2704 convention of verifying logger calls; `apiKeyRevoked` has no token parameter, so no secret can leak).
- [x] 6.8 Multi-instance exactly-once — covered by the win/lose scheduler tests (6.3) + the `buildGraceRevokeRequest` condition test (6.1). (The removed host-election tests no longer apply.)

**Build status:** `mvn test-compile` clean; scheduler + repository tests = 11 run, 0 failures.

## Remaining before merge

- **6.2 entity round-trip** integration test — needs a real DynamoDB table; folds into the IGDD-2707 end-to-end validation.
- **End-to-end** verification waits on IGDD-2707's renewal write-path landing on `origin` with the agreed contract — as of 2026-07-01 that branch still writes `status='superseded'` (should be `grace_period`) and persists `supersededByJti` (Hub reads `supersededBy`). Once fixed + merged, re-verify and adjust only if the contract changed.

Alerting is intentionally out of scope for now (Paul, 2026-07-01): the job emits started/succeeded/failed log messages; automated CloudWatch alarms are deferred to APHL as future work (spec in `runbook.md`). All Hub-side code, logging, and unit-testable logic for IGDD-2711 is complete.
