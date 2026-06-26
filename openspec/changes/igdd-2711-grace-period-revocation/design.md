## Context

API-key renewal is owned by Config Console (IGDD-2707). On `POST /api/apikeys/:jti/renew`, Config Console issues a new JWT/credential and updates the **old** `ApiKeyCredential`, setting `supersededBy = <new jti>` and `graceExpiresAt` (a configurable grace period). The old key's `status` stays `active` during the grace window so both old and new keys authenticate while the caller migrates. Once `graceExpiresAt` passes, the old key must be revoked.

IGDD-2705 already built the validation machinery this job reuses:
- `ApiKeyPrincipalProvider.evictCredential(String jti)` — evicts a `jti` from the in-memory credential cache (forcing re-validation against DynamoDB).
- The credential cache has a bounded TTL (`jwt.credential-cache-ttl`, default 5 minutes), so a status change in DynamoDB takes effect on every instance within that window even without an explicit eviction.

The `ApiKeyCredential` record uses `entityType = "ApiKeyCredential"` with sort key `{env}#{jti}`; lookups are environment-scoped by prefix (Hub's `findByEnvAndJti` does `find(env + "#" + jti)`).

## Goals / Non-Goals

**Goals:**
- Automatically transition superseded keys (`graceExpiresAt <= now`) from `active` to `revoked`.
- Emit a revocation audit event per revoked key and log per-run counts for operational visibility.
- Evict the revoked key from the acting instance's cache; rely on the credential-cache TTL for fleet-wide convergence.
- Run safely in a multi-instance deployment (no duplicate revocation storms).
- Provide a failure-detection signal and a runbook for manual remediation.

**Non-Goals:**
- Writing `graceExpiresAt` / `supersededBy` — that is Config Console's renew route (IGDD-2707). This change only *reads* them.
- Emitting `API_KEY_RENEWAL_SUPERSEDED` — that fires at renewal time in Config Console (IGDD-2712), not at grace expiry.
- Revoking keys for any reason other than expired grace (e.g. manual revoke remains Config Console's `DELETE /api/apikeys/:jti`).
- **Immediate cross-instance cache eviction** — out of scope for grace revocation; the credential-cache TTL provides adequate convergence (see D6). Urgent fleet-wide eviction is the manual-revoke path's concern (IGDD-2707).
- Hard-deleting credential records — superseded keys are revoked, not removed.
- Computing the grace duration or any business-day/holiday accounting — Config Console computes the absolute `graceExpiresAt` once, at renewal (IGDD-2707). This job only compares that timestamp to `now`.

## Decisions

### D1 — In-process `@Scheduled` job inside Hub, not a separate Lambda/ECS task
Hub is a long-running Spring Boot service that already hosts scheduled work (`StatusCheckScheduler`) and already wires `ApiKeyCredentialRepository`, `ApiKeyPrincipalProvider`, and `ApiKeyAuditLogger`. An in-process job reuses all of it with no new deployable artifact, IAM role, or infra. The acceptance criteria's "Lambda timeout or ECS task failure" is read generically as "the scheduled job failed to run" and is satisfied by Hub-level monitoring (D7). Trade-off: the job's health is coupled to Hub's; mitigated by the single-runner guard and the failure alarm.

### D2 — Add `graceExpiresAt` + `supersededBy` to Hub's `ApiKeyCredential`
The DynamoDB table is shared: Config Console writes these attributes, Hub reads them. Hub's `@DynamoDbBean` must declare them (`graceExpiresAt` serializes as an ISO-8601 string like `issuedAt`/`expiresAt`) or the sweep cannot see them. Both are nullable; records predating renewal have `null` and are never selected. Status literals are the lowercase set `active` / `revoked` / `expired` (see D8).

### D3 — Candidate selection: `status == active && graceExpiresAt != null && graceExpiresAt <= now`
A superseded-but-still-valid key stays `active` with a non-null `graceExpiresAt` (per IGDD-2707; there is no distinct grace status — see D8). The sweep selects exactly the `active` keys whose grace has elapsed. A normal active key has `graceExpiresAt == null` and is never selected. `supersededBy` is not part of the selection predicate (a non-null `graceExpiresAt` already implies supersession) but is carried into the audit event for traceability. Already-`revoked`/`expired` keys are skipped (idempotent: re-running the job revokes nothing new).

### D4 — Query strategy: environment-scoped prefix query, GSI deferred
The candidate finder uses the base repository's `findByType(env + "#")` (sort-key prefix query within the `ApiKeyCredential` entity type) then filters in memory on `status`/`graceExpiresAt`. At expected key volumes (one credential per jurisdiction-domain, low hundreds) this is adequate and avoids new index cost; a GSI on `status`+`graceExpiresAt` is the future escape hatch. The finder lives on `ApiKeyCredentialRepository` so the strategy is swappable without touching the scheduler.

### D5 — Single-runner guard for multi-instance safety
Hub runs multiple instances behind an ALB; every instance would otherwise run the sweep simultaneously. Revocation is idempotent (revoking an already-revoked key is a no-op write we skip), so correctness does not strictly require a leader — but duplicate writes and duplicate audit events are noisy. Reuse the host-ordering/leader approach already used by `StatusCheckScheduler` (or a conditional DynamoDB write lock) so a single instance performs the cycle.

### D6 — Local cache eviction; cross-instance broadcast intentionally out of scope
For each candidate the job sets `status = revoked`, `revokedAt = now`, `revokedBy = "system:grace-revocation"`, `store()`s the record, emits `API_KEY_REVOKED`, and evicts the credential from the **acting instance's** cache via `evictCredential(jti)`.

It does **not** broadcast the eviction to other instances. The acceptance criteria require only the DynamoDB status change and the audit event; they say nothing about cache propagation. Grace revocation is non-urgent (the key has been winding down for the whole grace window), and other instances re-validate against DynamoDB when their credential-cache entry expires (≤ `jwt.credential-cache-ttl`, default 5 minutes), at which point the `revoked` status takes effect — so the revocation converges fleet-wide within the cache TTL without any broadcast. Immediate all-instance propagation is the concern of Config Console's manual revoke (a security action, IGDD-2707) via `/rest/refresh`; wiring it from this scheduled sweep would require a non-HTTP entry point through `DbController`'s `@RolesAllowed`-guarded refresh path, weakening a security boundary for a non-requirement. If a future requirement demands sub-TTL propagation, that broadcast can be added behind the same path.

### D7 — Failure detection and runbook (AC #3)
Each run logs a structured completion record with counts; failures are logged at ERROR. A CloudWatch log-based alarm fires when either (a) an error is logged by the job, or (b) no successful-completion record is seen within an expected window (missed run). The operations runbook documents the manual remediation: confirm Hub health, and if needed run the manual revoke through Config Console (`DELETE /api/apikeys/:jti`) for any key whose `graceExpiresAt` has passed.

### D8 — Status model: lowercase `active` / `revoked` / `expired`, no distinct grace status (resolved 2026-06-25)
The authoritative source is Keith's **IGDD-2703 design record (ADR)**, which specifies **lowercase** status literals; the IGDD-2705 OpenSpec work followed it (`status` — one of `active`, `revoked`, `expired`). Paul confirmed the team standardizes on lowercase to avoid mixed case. There is **no `'Grace Period'` status**; grace is a `graceExpiresAt` timestamp on an otherwise-`active` key. Consequences:
- This job uses lowercase literals in its predicate (D3) and write (D6).
- **IGDD-2705's auth path needs no change** — its existing `"active".equals(getStatus())` already matches the ADR. (An earlier review note suggesting a casing fix is withdrawn; the discrepancy is on the Config Console side, not Hub.)
- **Config Console conforms**: it stores lowercase `active`/`revoked` in DynamoDB and capitalizes only for display in its UI layer (Palak), and drops the `'Grace Period'` status value its current branch defines.

## Risks / Trade-offs

- **Shared-attribute contract drift** — if Config Console's field names/semantics differ from `graceExpiresAt`/`supersededBy`, the sweep silently selects nothing. Mitigation: confirm attribute names against IGDD-2707's DynamoDB writes; add an integration check on a renewed-then-expired fixture.
- **Status-literal drift across tickets** — 2705 (auth), 2707 (CC writes), and 2711 (this revoke) all compare/write `status`. The lowercase decision (D8) must be applied consistently; a shared enum/constant would prevent future casing drift. The live risk is CC persisting mixed-case values — verify CC writes lowercase to DynamoDB (its display-layer capitalization must not leak into stored data).
- **Up-to-5-minute propagation lag** — see D6; acceptable because grace revocation is non-urgent. Escalate to a broadcast only if a sub-TTL requirement emerges.
- **Coupled health** — see D1; covered by D5 + D7.
- **Clock/timezone** — comparisons use UTC `Instant`s consistent with `issuedAt`/`expiresAt` serialization; no local-time arithmetic.

## Open Questions

- **Q1 — RESOLVED (team, 2026-06-24/25, grounded in the IGDD-2703 ADR).** The contract is settled enough to implement:
  1. Status literals: lowercase **`active` / `revoked`** (`expired` also valid); **no `'Grace Period'` status** (D8). Hub's 2705 auth needs no change; Config Console conforms to lowercase in storage and drops `'Grace Period'`.
  2. CC adds **`graceExpiresAt`** (absolute ISO-8601 timestamp, set on the old key at renewal) and **`supersededBy`** (new jti). Hub adds both to `ApiKeyCredential` and reads them.
  3. Grace key = `active` + non-null `graceExpiresAt`. Job selects `status == active && graceExpiresAt != null && graceExpiresAt <= now` → `revoked`.
  4. Sort key `{env}#{jti}` with `entityType = "ApiKeyCredential"` — confirmed by Paul against the dev table; matches Hub's existing `findByEnvAndJti`, no change.

  *Caveat:* CC's renewal write-path is not yet built, so end-to-end verification waits on IGDD-2707; Hub-side work proceeds against this agreed contract.
- **Q2** — Single-runner mechanism: reuse `StatusCheckScheduler`'s host-ordering vs. a dedicated conditional-write lock item. Decide during implementation based on how strict the "exactly once per cycle" requirement is.
- **Q3** — Run interval default (e.g. hourly) and whether it should be environment-configurable.
