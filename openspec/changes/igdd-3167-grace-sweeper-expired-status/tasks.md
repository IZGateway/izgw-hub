## 1. Entity

- [x] 1.1 Add `expiredAt` (`Instant`, nullable) and `expiredBy` (`String`, nullable) to
      `ApiKeyCredential`, parallel to `revokedAt`/`revokedBy` (serialized the same way, via the
      Enhanced Client's default `InstantAsStringAttributeConverter`).

## 2. Repository

- [x] 2.1 Add `ApiKeyCredentialRepository.STATUS_EXPIRED = "expired"`.
- [x] 2.2 Add `public static String resolveTerminalStatus(ApiKeyCredential credential)` — pure function:
      `expired` if `expiresAt != null && graceExpiresAt != null && !expiresAt.isAfter(graceExpiresAt)`,
      else `revoked` (covers the null-`expiresAt` guard case too, defaulting to current behavior).
      Public (not package-private) so the scheduler, in a different package, can resolve the same
      label independently.
- [x] 2.3 Replace `buildGraceRevokeRequest` with `buildGraceTerminationRequest(tableName, env, jti,
      terminalStatus, terminatedAt, terminatedBy)` — same conditional write shape (`status =
      grace_period`), but sets `expiredAt`/`expiredBy` when `terminalStatus == expired`, otherwise
      `revokedAt`/`revokedBy`, plus the `status` value itself and `updatedOn`/`updatedBy` as before.
- [x] 2.4 Replace `revokeIfGracePeriod` with `terminateIfGracePeriod(credential, terminatedAt,
      terminatedBy)` — resolves the terminal status internally, executes the conditional update, and
      returns `true`/`false` exactly as the old method did (condition-failure semantics unchanged).

## 3. Audit logger

- [x] 3.1 Add `API_KEY_EXPIRED` and `SYSTEM_GRACE_EXPIRATION` constants to `ApiKeyAuditLogger`.
- [x] 3.2 Add `apiKeyExpired(keyId, jurisdictionId, expiredBy, supersededBy)`, structurally parallel
      to `apiKeyRevoked` (no token/secret material).

## 4. Scheduler

- [x] 4.1 In the per-candidate loop (now `terminateCredential`), compute `terminalStatus =
      ApiKeyCredentialRepository.resolveTerminalStatus(credential)` and the matching actor
      (`SYSTEM_GRACE_EXPIRATION` or `SYSTEM_GRACE_REVOCATION`) before writing.
- [x] 4.2 Call `terminateIfGracePeriod(credential, now, actor)`; on a won write, emit
      `auditLogger.apiKeyExpired(...)` or `auditLogger.apiKeyRevoked(...)` matching the resolved
      status, then evict the local cache as before.
- [x] 4.3 Split `CycleResult`'s single `revoked` count into `expired` and `revoked` counts; updated the
      `GRACE_REVOCATION_RUN` log line to include both fields alongside `evaluated`.

## 5. Tests

- [x] 5.1 `ApiKeyCredentialRepositoryTests` — `resolveTerminalStatus`: `expiresAt < graceExpiresAt` →
      expired; `expiresAt == graceExpiresAt` → expired (boundary); `expiresAt > graceExpiresAt` →
      revoked; `expiresAt == null` → revoked (guard).
- [x] 5.2 `ApiKeyCredentialRepositoryTests` — `buildGraceTerminationRequest`: expired branch sets
      `expiredAt`/`expiredBy` and `status = expired`, and does NOT set `revokedAt`/`revokedBy`; revoked
      branch unchanged from today's assertions (adapted to the renamed method).
- [x] 5.3 `GracePeriodRevocationSchedulerTests` — a candidate with `expiresAt <= graceExpiresAt`
      results in `apiKeyExpired` (not `apiKeyRevoked`) and `CycleResult.expired() == 1`; a candidate
      with `graceExpiresAt < expiresAt` results in `apiKeyRevoked` and `CycleResult.revoked() == 1`; a
      mixed batch produces correct per-outcome counts and audit calls; lost conditional write still
      produces neither audit call (existing coverage, re-verified against the renamed method).

**Build status:** `mvn test-compile` clean; `mvn test -Dtest=ApiKeyCredentialRepositoryTests,GracePeriodRevocationSchedulerTests` → 17 run (12 + 5), 0 failures.

## Not done (out of scope, see design.md D1)

- Candidate *selection* (`findGraceRevocationCandidates`/`selectGraceCandidates`) is unchanged —
  still triggers on `graceExpiresAt <= now` only, not on `min(graceExpiresAt, expiresAt) <= now`.
  A credential whose `expiresAt` has passed while `graceExpiresAt` is still in the future is not
  yet a candidate at all; this change only fixes the label for credentials the sweep already
  processes. See design.md D1 for the rationale.
- No entity round-trip integration test for `expiredAt`/`expiredBy` (same gap noted for
  `graceExpiresAt`/`supersededBy` in IGDD-2711 — no DynamoDB Local/Testcontainers harness in this repo).
