## MODIFIED Requirements

### Requirement: Scheduled grace-period revocation sweep
Hub SHALL run a scheduled, in-process job that periodically terminates superseded API-key credentials whose grace period has expired. On each cycle the job SHALL query for candidates (`status == grace_period`, non-null `graceExpiresAt`, `graceExpiresAt <= now`, current environment) and, for each candidate, transition it to its correct terminal status:
- `expired` if the credential's own `expiresAt` is on or before `graceExpiresAt` (`expiresAt <= graceExpiresAt` — the key's own lifetime capped it first), or
- `revoked` if `graceExpiresAt` is before the credential's own `expiresAt` (the grace window was cut off before the key's own expiry).

The run interval SHALL be configurable (`apikey.grace-revocation.*`), and the job SHALL be guarded so that a given candidate is terminated — and audited — exactly once even when multiple Hub instances run the sweep concurrently.

#### Scenario: Grace period has passed, key had already reached its own expiry
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` has passed and whose `expiresAt <= graceExpiresAt`
- **WHEN** the scheduled job runs
- **THEN** the key's `status` is set to `expired` in DynamoDB, `expiredAt` is set to the current time, `expiredBy` is set to `system:grace-expiration`, `revokedAt`/`revokedBy` remain `null`, and an `API_KEY_EXPIRED` audit event is emitted

#### Scenario: Grace period has passed, cut off before the key's own expiry
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` has passed and whose `graceExpiresAt < expiresAt`
- **WHEN** the scheduled job runs
- **THEN** the key's `status` is set to `revoked` in DynamoDB, `revokedAt` is set to the current time, `revokedBy` is set to `system:grace-revocation`, `expiredAt`/`expiredBy` remain `null`, and an `API_KEY_REVOKED` audit event is emitted

#### Scenario: Grace period has not passed
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` has not yet passed
- **WHEN** the scheduled job runs
- **THEN** the key is not terminated and remains `grace_period` (and continues to authenticate)

#### Scenario: Idempotent re-run
- **GIVEN** a key was already terminated (to either `expired` or `revoked`) by a previous cycle
- **WHEN** the scheduled job runs again
- **THEN** the key is not re-written and no duplicate audit event is emitted for it

### Requirement: Termination audit events
When the job terminates a superseded credential it SHALL emit exactly one audit event via `ApiKeyAuditLogger`, matching the terminal status assigned:
- `API_KEY_EXPIRED`, containing at least: event type `API_KEY_EXPIRED`, `keyId` (the `jti`), `jurisdictionId`, `expiredBy` (`system:grace-expiration`), `supersededBy` (the renewing key's `jti`), and `timestamp`; or
- `API_KEY_REVOKED`, containing at least: event type `API_KEY_REVOKED`, `keyId` (the `jti`), `jurisdictionId`, `revokedBy` (`system:grace-revocation`), `supersededBy` (the renewing key's `jti`), and `timestamp`.

Neither event SHALL contain any token string or secret material.

#### Scenario: Expired audit event emitted
- **WHEN** the job marks a credential expired with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_EXPIRED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `expiredBy = system:grace-expiration`, `supersededBy = K2`, and a timestamp, and no token or secret material

#### Scenario: Revoked audit event emitted
- **WHEN** the job revokes a credential with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_REVOKED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `revokedBy = system:grace-revocation`, `supersededBy = K2`, and a timestamp, and no token or secret material

### Requirement: Operational visibility — per-run counts
Each execution of the job SHALL log, at a level visible in CloudWatch, the number of candidate keys evaluated, the number marked `expired`, and the number marked `revoked` in that run.

#### Scenario: Counts logged each run
- **WHEN** a cycle evaluates 5 candidate keys, marks 2 `expired`, and revokes 1
- **THEN** the run logs a structured record indicating 5 evaluated, 2 expired, and 1 revoked
