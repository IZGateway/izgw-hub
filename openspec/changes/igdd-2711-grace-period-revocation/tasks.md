## 0. Confirm upstream contract (IGDD-2707)

- [ ] 0.1 Confirm the DynamoDB attribute names/types Config Console writes on renewal — assumed `graceExpiresAt` (ISO-8601 `Instant`) and `supersededBy` (`String` jti). Reconcile against IGDD-2707's implementation / IGDD-2703 ADR before relying on them (design Q1).
- [ ] 0.2 Confirm a superseded key keeps `status = active` during the grace window (per IGDD-2707 renew step 3) — this is the selection predicate's assumption.

## 1. Entity and Repository

- [ ] 1.1 Add `graceExpiresAt` (`Instant`, nullable) and `supersededBy` (`String`, nullable) to `ApiKeyCredential`; annotate `graceExpiresAt` with `InstantAsStringAttributeConverter` to match `issuedAt`/`expiresAt`.
- [ ] 1.2 Add a finder to `ApiKeyCredentialRepository` returning grace-revocation candidates for the current environment: `status == active && graceExpiresAt != null && graceExpiresAt <= now`. Implement as an environment-prefixed scan/query with a filter (design D4); keep the strategy encapsulated in the repository.
- [ ] 1.3 Determine current environment for the sort-key prefix consistently with how `ApiKeyPrincipalProvider` derives `env` (e.g. `SystemUtils`); reuse the same source.

## 2. Audit event

- [x] 2.1 Add an `API_KEY_REVOKED` constant and an emit method to `ApiKeyAuditLogger` — fields: eventType, `keyId` (jti), `jurisdictionId`, `revokedBy`, `supersededBy`, `timestamp`; no token/secret material. Follow the existing `apiKeyUsed`/`apiKeyAuthFailed` `Markers2` pattern. (Also added `SYSTEM_GRACE_REVOCATION` constant. Uses 5-pair `append` + `.and()` for the 6th field since `Markers2.append` tops out at 5 pairs.)

## 3. Scheduler

- [x] 3.1 Enable Spring scheduling (added `@EnableScheduling` on `GracePeriodRevocationScheduler`, gated by `@ConditionalOnProperty` so the scheduling infra only activates when the job is enabled — does not disturb `StatusCheckScheduler`'s own executor).
- [x] 3.2 Create `GracePeriodRevocationScheduler` (`@Component`, `gov.cdc.izgateway.hub.security`) — injects `ApiKeyCredentialRepository`, `ApiKeyAuditLogger`, `ApiKeyPrincipalProvider`; `@Scheduled` entrypoint (`scheduledRun`) wraps `runRevocationCycle` in try/catch with MDC eventId and ERROR-on-failure logging.
- [ ] 3.3 Implement the single-runner guard for multi-instance safety (design D5) — reuse `StatusCheckScheduler`'s host-ordering approach or a conditional-write lock; document the choice (design Q2). **STUBBED**: `isDesignatedRunner()` returns `true` with TODO.
- [x] 3.4 Implement the cycle: query candidates → for each, set `status=revoked`, `revokedAt=now`, `revokedBy="system:grace-revocation"`, `store()`; skip records already `revoked` (idempotency); accumulate evaluated/revoked counts.
- [x] 3.5 Emit the `API_KEY_REVOKED` audit event per revoked key (task 2.1). (Passes `supersededBy=null` pending task 1.1 — TODO in code.)
- [ ] 3.6 Propagate eviction: publish a `RefreshRequest` carrying the revoked `jti` via the existing `RefreshQueueService` path (mirror Config Console's `/rest/refresh` revoke) and call `apiKeyPrincipalProvider.evictCredential(jti)` locally (design D6). **PARTIAL**: local `evictCredential` done; cross-instance broadcast STUBBED with TODO — `RefreshQueueService` is constructed privately inside `DbController` and `getRefreshed` is `@RolesAllowed`-guarded, so a non-HTTP broadcast entry point is needed.
- [x] 3.7 Log per-run counts (evaluated / revoked) at a CloudWatch-visible level (AC #4). (`GRACE_REVOCATION_RUN` structured log at INFO.)

## 4. Configuration

- [x] 4.1 Add `apikey.grace-revocation.*` properties — `GracePeriodRevocationProperties` (`@ConfigurationProperties`): `enabled` (boolean, default **false**), `interval` (Duration, default 1h), `initialDelay` (Duration, default 5m). (Note: defaulted `enabled` to false, not true — safer while the IGDD-2707 contract is unconfirmed.)
- [x] 4.2 Add config stubs to `application.yml` with documented defaults; allow per-environment override via `APIKEY_GRACE_REVOCATION_ENABLED` (design Q3).
- [x] 4.3 Gate the scheduler on `apikey.grace-revocation.enabled` (`@ConditionalOnProperty`, no `matchIfMissing` → disabled unless explicitly `true`).

## 5. Monitoring and Runbook (AC #3)

- [ ] 5.1 Define a CloudWatch log-based alarm for job failure: triggers on a logged job error and/or a missing successful-completion heartbeat within the expected window. (Coordinate with the infra/terraform repo if alarms live there.)
- [ ] 5.2 Add an operations runbook entry: how to detect the job is not running, and the manual remediation — revoke expired-grace keys via Config Console `DELETE /api/apikeys/:jti`. Link from RELEASE_NOTES / docs as appropriate.

## 6. Tests

- [ ] 6.1 Repository finder — selects `active` + past `graceExpiresAt`; excludes future `graceExpiresAt`, `null` `graceExpiresAt`, and non-`active` status; scoped to current env.
- [ ] 6.2 Entity round-trip — `graceExpiresAt`/`supersededBy` serialize/deserialize via DynamoDB without precision loss; legacy record with both null deserializes cleanly.
- [x] 6.3 Scheduler — grace passed → record set to `revoked` with `revokedAt`/`revokedBy=system:grace-revocation`, audit event emitted, eviction propagated. (`activeCandidate_isRevokedAuditedAndEvicted`)
- [~] 6.4 Scheduler — grace not passed → record untouched. Covered indirectly: candidate selection lives in the (stubbed) repository finder, so the scheduler test covers the already-revoked skip case (`nonActiveCandidate_isSkipped`) and empty case (`noCandidates_performsNoRevocations`). A true "grace not passed" test belongs with the finder once implemented (task 6.1).
- [x] 6.5 Scheduler — idempotent re-run over an already-revoked record → no write, no duplicate audit event. (`nonActiveCandidate_isSkipped`, `multipleCandidates_revokesOnlyActiveOnes`)
- [x] 6.6 Scheduler — per-run counts (evaluated / revoked) are logged. (verified via cycle log lines; emitted by `runRevocationCycle`)
- [ ] 6.7 `ApiKeyAuditLogger` — `API_KEY_REVOKED` emits the expected fields and no token/secret material.
- [ ] 6.8 Single-runner guard — only one instance performs revocation per cycle (unit-level around the guard). (Blocked on task 3.3.)

**Build status:** `mvn test-compile` clean; `GracePeriodRevocationSchedulerTests` = 4 run, 0 failures.
