## Why

When a jurisdiction renews an API key, Config Console (IGDD-2707) issues a new credential but leaves the old one **active** for a configurable grace period — it stamps the old `ApiKeyCredential` with `supersededBy = <new jti>` and `graceExpiresAt`, so both keys authenticate while the caller cuts over. Nothing currently revokes the old key once that grace window closes. Without an automated sweep, superseded keys accumulate as indefinitely-valid credentials, defeating the purpose of renewal and widening the credential attack surface.

This change adds a scheduled job inside Hub that revokes superseded API keys after their grace period expires (IGDD-2711, User Story 10).

## What Changes

- **Modified**: `ApiKeyCredential` entity — add `graceExpiresAt` (`Instant`, nullable) and `supersededBy` (`String`, nullable). These are written by Config Console's renew route (IGDD-2707) and read by Hub. Hub's `@DynamoDbBean` must map them so the sweep can act on them.
- **Modified**: `ApiKeyCredentialRepository` — add a finder that returns credentials eligible for grace-period revocation (`status == active && graceExpiresAt != null && graceExpiresAt <= now`), scoped to the current environment.
- **Modified**: `ApiKeyAuditLogger` — add an `API_KEY_REVOKED` audit event for grace-period revocation (`revokedBy = "system:grace-revocation"`), consistent with Config Console's manual-revoke event of the same type.
- **New**: `GracePeriodRevocationScheduler` — an in-process scheduled job that periodically queries for expired-grace superseded keys, revokes each in DynamoDB, emits the audit event, propagates cache eviction to all Hub instances, and logs per-run counts (evaluated / revoked). Includes a single-runner guard so only one Hub instance performs revocation per cycle.
- **Modified**: configuration — add `apikey.grace-revocation.*` properties (enabled flag, run interval) and enable Spring scheduling.

## Capabilities

### New Capabilities

- `grace-period-revocation`: the scheduled sweep that finds superseded API-key credentials whose grace period has expired, transitions them to `revoked`, emits a revocation audit event, propagates cache eviction across Hub instances, and emits operational metrics (counts evaluated / revoked).

### Modified Capabilities

- `api-key-credential`: the `ApiKeyCredential` entity gains `graceExpiresAt` and `supersededBy`; the repository gains a finder for grace-revocation candidates.

## Impact

- **DynamoDB shared table** — `ApiKeyCredential` gains two attributes written by Config Console (IGDD-2707) and read by Hub. No table/key structure change; new optional attributes only. Older records without these attributes deserialize with `null` and are never selected by the sweep.
- **Cache coherence** — revocation by the job must propagate to every Hub instance's `credentialCache`/revoked sentinel via the existing `RefreshQueueService` SQS mechanism (the same path Config Console triggers through `/rest/refresh`); otherwise a revoked key stays valid in-cache until TTL.
- **Scheduling** — Hub gains an `@EnableScheduling`-driven job. Hub runs multi-instance behind an ALB, so the job needs a single-runner guard to avoid redundant writes/audit noise.
- **Observability / Ops** — per-run counts logged for CloudWatch; a log-based CloudWatch alarm plus an operations runbook entry cover the "job failed to run" acceptance criterion (AC #3).
- **Coordination** — IGDD-2707 owns the write side (`graceExpiresAt`/`supersededBy`, `API_KEY_RENEWAL_SUPERSEDED` at renewal time). IGDD-2712 owns the broader audit-logging scheme. This change emits the *grace-expiry* `API_KEY_REVOKED` event; it does not emit `API_KEY_RENEWAL_SUPERSEDED` (that fires at renewal, in Config Console).
