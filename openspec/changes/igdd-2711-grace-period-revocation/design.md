## Context

API-key renewal is owned by Config Console (IGDD-2707). On `POST /api/apikeys/:jti/renew`, Config Console issues a new JWT/credential and updates the **old** `ApiKeyCredential`, setting `supersededBy = <new jti>` and `graceExpiresAt` (a configurable grace period). The old key's `status` stays `'Active'` during the grace window so both old and new keys authenticate while the caller migrates. Once `graceExpiresAt` passes, the old key must be revoked.

IGDD-2705 already built the validation and revocation-propagation machinery this job reuses:
- `ApiKeyPrincipalProvider.evictCredential(String jti)` — evicts a `jti` from the in-memory credential cache (forcing re-validation against DynamoDB).
- `RefreshQueueService.RefreshRequest(reset, eventId, senderHost, senderRegion, jti)` + `refreshLoop` — when a refresh message carries a `jti`, every Hub instance calls `evictCredential(jti)`. This is the existing SQS inter-instance propagation path, also triggered by Config Console's manual revoke via `/rest/refresh`.

The `ApiKeyCredential` record uses `entityType = "ApiKeyCredential"` with sort key `{env}#{jti}`; lookups are environment-scoped by prefix (Hub's `findByEnvAndJti` does `find(env + "#" + jti)`).

## Goals / Non-Goals

**Goals:**
- Automatically transition superseded keys (`graceExpiresAt <= now`) from `'Active'` to `'Revoked'`.
- Emit a revocation audit event per revoked key and log per-run counts for operational visibility.
- Propagate revocation to every Hub instance's cache using the existing refresh mechanism.
- Run safely in a multi-instance deployment (no duplicate revocation storms).
- Provide a failure-detection signal and a runbook for manual remediation.

**Non-Goals:**
- Writing `graceExpiresAt` / `supersededBy` — that is Config Console's renew route (IGDD-2707). This change only *reads* them.
- Emitting `API_KEY_RENEWAL_SUPERSEDED` — that fires at renewal time in Config Console (IGDD-2712), not at grace expiry.
- Revoking keys for any reason other than expired grace (e.g. manual revoke remains Config Console's `DELETE /api/apikeys/:jti`).
- Hard-deleting credential records — superseded keys are revoked, not removed.
- Computing the grace duration or any business-day/holiday accounting — Config Console computes the absolute `graceExpiresAt` once, at renewal (IGDD-2707). This job only compares that timestamp to `now`.

## Decisions

### D1 — In-process `@Scheduled` job inside Hub, not a separate Lambda/ECS task
Hub is a long-running Spring Boot service that already hosts scheduled work (`StatusCheckScheduler`) and already wires `ApiKeyCredentialRepository`, `ApiKeyPrincipalProvider`, `ApiKeyAuditLogger`, and the SQS refresh path. An in-process job reuses all of it with no new deployable artifact, IAM role, or infra. The acceptance criteria's "Lambda timeout or ECS task failure" is read generically as "the scheduled job failed to run" and is satisfied by Hub-level monitoring (D5). Trade-off: the job's health is coupled to Hub's; mitigated by the single-runner guard and the failure alarm.

### D2 — Add `graceExpiresAt` + `supersededBy` to Hub's `ApiKeyCredential`
The DynamoDB table is shared: Config Console writes these attributes, Hub reads them. Hub's `@DynamoDbBean` must declare them (with `InstantAsStringAttributeConverter` for `graceExpiresAt`, matching `issuedAt`/`expiresAt`) or the sweep cannot see them. Both are nullable; records predating renewal have `null` and are never selected. Status literals are the capitalized set `'Active'` / `'Revoked'` (see D8).

### D3 — Candidate selection: `status == 'Active' && graceExpiresAt != null && graceExpiresAt <= now`
A superseded-but-still-valid key stays `'Active'` with a non-null `graceExpiresAt` (per IGDD-2707; there is no distinct grace status — see D8). The sweep selects exactly the `'Active'` keys whose grace has elapsed. A normal active key has `graceExpiresAt == null` and is never selected. `supersededBy` is not part of the selection predicate (a non-null `graceExpiresAt` already implies supersession) but is carried into the audit event for traceability. Already-`'Revoked'` keys are skipped (idempotent: re-running the job revokes nothing new).

### D4 — Query strategy: environment-scoped scan, GSI deferred
The current repository only supports `GetItem` by sort key. Finding candidates requires a scan/query. At expected key volumes (one credential per jurisdiction-domain, low hundreds), a periodic environment-prefixed scan filtering on `status`/`graceExpiresAt` is acceptable and avoids new index cost/complexity. A GSI on `status`+`graceExpiresAt` is a documented future optimization if volume grows. The finder lives on `ApiKeyCredentialRepository` so the strategy is swappable without touching the scheduler.

### D5 — Single-runner guard for multi-instance safety
Hub runs multiple instances behind an ALB; every instance would otherwise run the sweep simultaneously. Revocation is idempotent (revoking an already-revoked key is a no-op write we skip), so correctness does not strictly require a leader — but duplicate writes and duplicate audit/eviction events are noisy. Reuse the host-ordering/leader approach already used by `StatusCheckScheduler` (or a conditional DynamoDB write lock) so a single instance performs the cycle. Other instances still converge via the SQS eviction broadcast.

### D6 — Revocation write + cross-instance eviction
For each candidate the job:
1. Sets `status = 'Revoked'`, `revokedAt = now`, `revokedBy = "system:grace-revocation"`, and `store()`s the record.
2. Emits `API_KEY_REVOKED` via `ApiKeyAuditLogger` (`keyId = jti`, `jurisdictionId`, `revokedBy`, `supersededBy`, `timestamp`).
3. Propagates eviction to all instances by publishing a `RefreshRequest` carrying the `jti` through the existing `RefreshQueueService` path (and evicts locally via `evictCredential(jti)`), mirroring how Config Console's manual revoke propagates. This ensures no instance re-validates the revoked key from a warm cache.

### D7 — Failure detection and runbook (AC #3)
Each run logs a structured start/finish heartbeat with counts. A CloudWatch log-based alarm fires when either (a) an unhandled error is logged by the job, or (b) no successful-completion heartbeat is seen within an expected window (missed run). The operations runbook documents the manual remediation: confirm Hub health, and if needed run the equivalent manual revoke through Config Console (`DELETE /api/apikeys/:jti`) for any key whose `graceExpiresAt` has passed.

### D8 — Status model: `'Active'` / `'Revoked'`, no distinct grace status (team decision 2026-06-25)
Per Keith (Wednesday standup), there is **no `'Grace Period'` status**. Grace is represented by a `graceExpiresAt` timestamp on an otherwise-`'Active'` key, not by a status value. Literals are **capitalized** (`'Active'`, `'Revoked'`) to match Config Console. This has two consequences:
- This job uses the capitalized literals in its predicate (D3) and write (D6).
- **IGDD-2705's auth path needs only a casing fix** (`"active"` → `"Active"`); because grace keys remain `'Active'`, they authenticate naturally with no special grace handling. (Config Console must drop the `'Grace Period'` value its current branch defines.)

## Risks / Trade-offs

- **Shared-attribute contract drift** — if Config Console's field names/semantics differ from `graceExpiresAt`/`supersededBy`, the sweep silently selects nothing. Mitigation: confirm attribute names against IGDD-2707's DynamoDB writes; add an integration check on a renewed-then-expired fixture.
- **Status-literal drift across tickets** — 2705 (auth), 2707 (CC writes), and 2711 (this revoke) all compare/write `status`. A shared enum/constant would prevent casing drift; at minimum the casing decision (D8) must be applied consistently. Flag on the 2705 PR.
- **Scan cost at scale** — see D4; GSI is the escape hatch.
- **Coupled health** — see D1; covered by D5 + D7.
- **Clock/timezone** — comparisons use UTC `Instant`s consistent with `issuedAt`/`expiresAt` serialization; no local-time arithmetic.

## Open Questions

- **Q1 — RESOLVED (team, 2026-06-24/25).** The contract is now settled enough to implement:
  1. Status literals: capitalized **`'Active'` / `'Revoked'`**; **no `'Grace Period'` status** (D8). CC must drop `'Grace Period'` from its model; 2705 needs the `"active"`→`"Active"` casing fix.
  2. CC adds **`graceExpiresAt`** (absolute ISO-8601 timestamp, set on the old key at renewal) and **`supersededBy`** (new jti). Hub adds both to `ApiKeyCredential` and reads them.
  3. Grace key = `'Active'` + non-null `graceExpiresAt`. Job selects `status == 'Active' && graceExpiresAt != null && graceExpiresAt <= now` → `'Revoked'`.
  4. Sort key `{env}#{jti}` with `entityType = "ApiKeyCredential"` — confirmed by Paul against the dev table; matches Hub's existing `findByEnvAndJti`, no change.

  *Caveat:* CC's renewal write-path is not yet built, so end-to-end verification waits on IGDD-2707; Hub-side work proceeds against this agreed contract.
- **Q2** — Single-runner mechanism: reuse `StatusCheckScheduler`'s host-ordering vs. a dedicated conditional-write lock item. Decide during implementation based on how strict the "exactly once per cycle" requirement is.
- **Q3** — Run interval default (e.g. hourly) and whether it should be environment-configurable.
