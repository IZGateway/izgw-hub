## ADDED Requirements

### Requirement: Scheduled grace-period revocation sweep
Hub SHALL run a scheduled, in-process job that periodically terminates superseded API-key credentials whose grace period has expired. On each cycle the job SHALL query for candidates (`status == grace_period`, non-null `graceExpiresAt`, `graceExpiresAt <= now`) across all `ApiKeyCredential` records and, for each candidate, transition it to its resolved **terminal status** (IGDD-3167):

- `expired` — when the credential's own `expiresAt` is on or before its `graceExpiresAt`, i.e. the key's own lifetime capped it first. Writes `expiredAt`/`expiredBy`.
- `revoked` — otherwise, including the defensive case of a missing `expiresAt`. Writes `revokedAt`/`revokedBy`.

Exactly one timestamp/actor pair SHALL be written, leaving the other `null`.

The run interval SHALL be configurable (`apikey.grace-revocation.*`) and the job SHALL be disabled unless `apikey.grace-revocation.enabled=true`. In a multi-instance Hub deployment every enabled instance runs the sweep each cycle; each candidate SHALL be terminated with a conditional DynamoDB write (`status = grace_period` as the condition) so that a given candidate is terminated — and audited — exactly once across the fleet, with no dependency on runner election or host coordination.

#### Scenario: Grace period has passed and the grace window ended first
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` timestamp has passed
- **AND** its own `expiresAt` is after `graceExpiresAt` (or absent)
- **WHEN** the scheduled job runs
- **THEN** the key's `status` is set to `revoked` in DynamoDB, `revokedAt` is set to the current time, `revokedBy` is set to `system:grace-revocation`, and an `API_KEY_REVOKED` audit event is emitted

#### Scenario: Grace period has passed and the key's own expiry came first
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` timestamp has passed
- **AND** its own `expiresAt` is on or before `graceExpiresAt`
- **WHEN** the scheduled job runs
- **THEN** the key's `status` is set to `expired` in DynamoDB, `expiredAt` is set to the current time, `expiredBy` is set to `system:grace-expiration`, and an `API_KEY_EXPIRED` audit event is emitted
- **AND** `revokedAt`/`revokedBy` remain `null`

#### Scenario: Grace period has not passed
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` has not yet passed
- **WHEN** the scheduled job runs
- **THEN** the key is not revoked and remains `grace_period` (and continues to authenticate)

#### Scenario: Idempotent re-run
- **GIVEN** a key was already terminated (to `expired` or `revoked`) by a previous cycle
- **WHEN** the scheduled job runs again
- **THEN** the key is not re-written and no duplicate audit event is emitted for it

#### Scenario: Concurrent instances terminate a key exactly once
- **GIVEN** two Hub instances run the sweep in the same cycle and both select the same `grace_period` candidate
- **WHEN** both attempt the conditional write
- **THEN** exactly one write succeeds and only that instance emits the audit event and evicts its local cache; the other observes a conditional-check failure and emits nothing

### Requirement: Revocation audit event
When the job revokes a superseded credential it SHALL emit an `API_KEY_REVOKED` audit event via `ApiKeyAuditLogger`, containing at least: event type `API_KEY_REVOKED`, `keyId` (the `jti`), `jurisdictionId`, `revokedBy` (`system:grace-revocation`), `supersededBy` (the renewing key's `jti`), and `timestamp`. The event SHALL NOT contain any token string or secret material.

#### Scenario: Audit event emitted on revocation
- **WHEN** the job revokes a credential with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_REVOKED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `revokedBy = system:grace-revocation`, `supersededBy = K2`, and a timestamp, and no token or secret material

### Requirement: Local cache eviction on revocation
After revoking a credential in DynamoDB, the job SHALL evict the credential from the acting instance's credential cache so that instance stops serving it immediately. The job SHALL NOT broadcast the eviction to other instances: grace revocation is non-urgent, and other instances converge when their credential-cache entries expire (≤ `jwt.credential-cache-ttl`) and re-validate against DynamoDB. Immediate fleet-wide eviction is the concern of Config Console's manual revoke path (IGDD-2707), not this scheduled sweep.

#### Scenario: Revoked key is evicted on the acting instance
- **WHEN** the job revokes a credential with `jti = K1`
- **THEN** the acting instance evicts `K1` from its credential cache, and other instances cease to serve `K1` within the credential-cache TTL when they re-validate against DynamoDB

### Requirement: Operational visibility — per-run counts
Each execution of the job SHALL log, at a level visible in CloudWatch, the number of candidate keys evaluated and the number revoked in that run.

#### Scenario: Counts logged each run
- **WHEN** a cycle evaluates 5 candidate keys and revokes 2
- **THEN** the run logs a structured record indicating 5 evaluated and 2 revoked

### Requirement: Failure detection and manual remediation
The job's execution SHALL be observable from structured logs so that a failure to run (unhandled error, or a missed run within the expected window) can be detected. Each cycle SHALL emit a `GRACE_REVOCATION_STARTED` event at the start and either a `GRACE_REVOCATION_RUN` event (success, with counts) or a `GRACE_REVOCATION_FAILED` event (ERROR level, with the exception) at the end. A failure in one candidate SHALL NOT abort the rest of the sweep.

Automated alarms on these events are deferred: the [operations runbook](https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/1011417090/Runbook+Grace-Period+Revocation+Job) documents the CloudWatch metric-filter/alarm definitions for the environment owner (APHL) to provision, and SHALL document a manual remediation procedure (revoke via Config Console) for expired-grace keys when the job is not running.

#### Scenario: Job failure is logged for detection
- **GIVEN** the scheduled job throws during a cycle
- **WHEN** the exception reaches `scheduledRun()`
- **THEN** a `GRACE_REVOCATION_FAILED` event is logged at ERROR with the exception, no `GRACE_REVOCATION_RUN` event is emitted for that cycle, and the scheduler thread survives to run the next cycle

#### Scenario: Missed run is detectable
- **GIVEN** the job is enabled with a 1-hour interval
- **WHEN** no `GRACE_REVOCATION_RUN` event has been logged within the expected window
- **THEN** the runbook's "missed run" condition applies and the documented manual remediation procedure is followed
