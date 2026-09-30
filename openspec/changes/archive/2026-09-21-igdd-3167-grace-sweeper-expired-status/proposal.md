## Why

`GracePeriodRevocationScheduler` (IGDD-2711) transitions every grace-expired credential to
`revoked`, regardless of why the grace window ended. It never writes `expired` and never compares
the credential's own `expiresAt` to `graceExpiresAt`. Per the credential state model the two
outcomes are distinct: the JWT `exp` caps validity, so the *effective* grace end is
`min(graceExpiresAt, expiresAt)`. A key that simply lived out its own lifetime should be recorded
as `expired`; only a key cut off *before* its own expiry (grace window closed first) should be
recorded as `revoked`. Today every aged-out key is mislabeled `revoked`, so the persisted status and
the audit trail cannot answer "did this key expire naturally, or was it cut off early?" — a
question IZ Gateway operators need answered when reviewing credential history.

This is a status-accuracy/audit change only. Enforcement is unaffected: both `expired` and
`revoked` are non-usable statuses, and the JWT `exp` already blocks time-expiry at request time
independent of this job.

## What Changes

- **Modified**: `ApiKeyCredential` entity — add `expiredAt` (`Instant`, nullable) and `expiredBy`
  (`String`, nullable), parallel to the existing `revokedAt`/`revokedBy`, so an `expired` transition
  has its own terminal timestamp/actor instead of leaving `revokedAt`/`revokedBy` populated (which
  would misrepresent the outcome as a revocation).
- **Modified**: `ApiKeyCredentialRepository` — add `resolveTerminalStatus(credential)`, a pure
  function that compares `expiresAt` to `graceExpiresAt` (`expiresAt <= graceExpiresAt` → `expired`,
  else `revoked`). Replace `revokeIfGracePeriod`/`buildGraceRevokeRequest` with
  `terminateIfGracePeriod`/`buildGraceTerminationRequest`, which write `status` plus either
  `expiredAt`/`expiredBy` or `revokedAt`/`revokedBy` depending on the resolved status, still gated
  by the same conditional write (`status = grace_period`) that gives exactly-once semantics across
  instances.
- **Modified**: `ApiKeyAuditLogger` — add an `API_KEY_EXPIRED` audit event
  (`apiKeyExpired(keyId, jurisdictionId, expiredBy, supersededBy)`) and a
  `SYSTEM_GRACE_EXPIRATION` actor constant, parallel to the existing `API_KEY_REVOKED` /
  `SYSTEM_GRACE_REVOCATION`.
- **Modified**: `GracePeriodRevocationScheduler` — compute the terminal status per candidate before
  writing (via `resolveTerminalStatus`), pass the matching actor into the repository write, and emit
  `apiKeyExpired` or `apiKeyRevoked` accordingly. Per-run logging splits the `revoked` count into
  `expired`/`revoked` counts for operational visibility.

Candidate *selection* (`findGraceRevocationCandidates` / `selectGraceCandidates`) is unchanged: the
sweep still triggers on `graceExpiresAt <= now`, per the existing AC precondition ("when the
sweeper processes a grace_period credential whose effective grace end has passed"). This change
only affects the terminal status assigned once a credential is being processed.

## Capabilities

### Modified Capabilities

- `api-key-credential`: `ApiKeyCredential` gains `expiredAt`/`expiredBy`.
- `grace-period-revocation`: the sweep now sets `expired` or `revoked` based on comparing
  `expiresAt` to `graceExpiresAt`, and emits the matching audit event.

## Impact

- **DynamoDB shared table** — two new optional attributes on `ApiKeyCredential`, written only by
  this job. No key-structure change; legacy records without them deserialize with `null`.
- **Audit trail** — a new `API_KEY_EXPIRED` event type alongside the existing `API_KEY_REVOKED`;
  consumers of the audit log (if any) that assumed every grace-sweep outcome was `API_KEY_REVOKED`
  will now also see `API_KEY_EXPIRED`.
- **Monitoring** — `GRACE_REVOCATION_RUN`'s `revoked` count field now reflects only true revocations
  (an `expired` field is added alongside it). The event type itself, and the "missed run" / "job
  failure" alarm design in the IGDD-2711 runbook (keyed on event presence, not count values), are
  unaffected.
- **No enforcement change** — `expired` and `revoked` remain equally non-usable; JWT `exp` already
  blocks time-expired tokens at request time regardless of persisted status.
