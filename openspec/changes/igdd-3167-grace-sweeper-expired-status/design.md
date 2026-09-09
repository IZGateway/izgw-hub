## Context

`GracePeriodRevocationScheduler` (IGDD-2711) already selects `grace_period` credentials whose
`graceExpiresAt <= now` and transitions every one of them to `revoked` via a conditional DynamoDB
write (exactly-once across instances). IGDD-3167 asks for the terminal status to distinguish
*expired* (the key reached its own `expiresAt`) from *revoked* (the grace window closed before the
key's own expiry). See `GracePeriodRevocationScheduler.java`, `ApiKeyCredentialRepository.java`,
and the IGDD-2711 design doc (D3, D5, D8) for the existing mechanics this change builds on.

## Decisions

### D1 — Selection predicate is unchanged; only the terminal label changes

The AC's precondition is "a `grace_period` credential whose effective grace end has passed," and
defines effective grace end as `min(graceExpiresAt, expiresAt)`. The current finder
(`selectGraceCandidates`) triggers only on `graceExpiresAt <= now`. These two conditions differ
exactly when `expiresAt <= now < graceExpiresAt` — a key that reached its own expiry early but is
still nominally inside its grace window.

**Decision: leave the finder untouched.** The AC says *when the sweeper processes* such a
credential, set the status thus — it does not require processing every credential whose `expiresAt`
alone has passed while `graceExpiresAt` is still in the future. The ticket's Background frames the
gap as "never writes expired and never compares `expiresAt` to `graceExpiresAt`" — a mislabeling
gap on credentials already being processed, not a timeliness gap in *which* credentials are swept.
Every credential the sweep does process gets the correct label under this change.

Sweeping early on `expiresAt` alone would be a legitimate follow-on (timelier `expired` labeling for
a key still nominally in grace), but it enlarges scope (new finder predicate, `expiresAt`-null
handling, additional finder tests) beyond what this AC requires, and JWT `exp` already blocks the
key from authenticating during that window regardless of persisted status. Not pursued here.

### D2 — Add `expiredAt`/`expiredBy`; do not reuse `revokedAt`/`revokedBy` for expired outcomes

`expiresAt` and `graceExpiresAt` already exist on `ApiKeyCredential` — they are *inputs* to the
label decision (set once, at issuance / at renewal, never touched by this job), not outputs of it.
There was no field recording *when the sweep flipped a credential's status*, for either outcome —
that's what `revokedAt`/`revokedBy` already did for `revoked`, so `expired` needs the equivalent.

Rejected: writing `revokedBy = system:grace-revocation` on a credential labeled `expired` — this
directly contradicts the ticket's goal (the audit trail should reflect *why* the key stopped being
usable) and would leave a `revokedAt` timestamp on a record that was never revoked.

Chosen: mirror the existing pair exactly — `expiredAt` (`Instant`, nullable) and `expiredBy`
(`String`, nullable) — written only on the `expired` branch, leaving `revokedAt`/`revokedBy` null
for those records (and vice versa). A new `SYSTEM_GRACE_EXPIRATION` actor constant
(`system:grace-expiration`) is used for `expiredBy` so the actor string itself doesn't say
"revocation" for a credential that merely expired; `SYSTEM_GRACE_REVOCATION` is unchanged for the
`revoked` branch.

### D3 — Exactly-once semantics preserved

The conditional write's condition expression (`status = grace_period`) is unchanged — only the
terminal status *value* and which timestamp/actor attributes are set are parameterized by
`resolveTerminalStatus`. The scheduler computes the label itself (via the same pure
`resolveTerminalStatus`) before calling the repository, so it knows which audit event to emit on a
won write without a re-read; the repository independently resolves the same label to pick the
correct attributes to write. Both calls are pure functions of the same credential snapshot, so they
always agree.
