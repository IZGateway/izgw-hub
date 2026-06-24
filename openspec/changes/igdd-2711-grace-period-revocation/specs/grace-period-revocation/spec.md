## ADDED Requirements

### Requirement: Scheduled grace-period revocation sweep
Hub SHALL run a scheduled, in-process job that periodically revokes superseded API-key credentials whose grace period has expired. On each cycle the job SHALL query for candidates (`status == active`, non-null `graceExpiresAt`, `graceExpiresAt <= now`, current environment) and, for each candidate, transition it to revoked.

The run interval SHALL be configurable (`apikey.grace-revocation.*`), and the job SHALL be guarded so that, in a multi-instance Hub deployment, a single instance performs revocation per cycle.

#### Scenario: Grace period has passed
- **GIVEN** a key has been renewed and its `graceExpiresAt` timestamp has passed
- **WHEN** the scheduled job runs
- **THEN** the superseded key's `status` is set to `revoked` in DynamoDB, `revokedAt` is set to the current time, `revokedBy` is set to `system:grace-revocation`, and a revocation audit event is emitted

#### Scenario: Grace period has not passed
- **GIVEN** a key has been renewed but its `graceExpiresAt` has not yet passed
- **WHEN** the scheduled job runs
- **THEN** the key is not revoked and remains `active`

#### Scenario: Idempotent re-run
- **GIVEN** a key was already revoked by a previous cycle
- **WHEN** the scheduled job runs again
- **THEN** the key is not re-written and no duplicate revocation audit event is emitted for it

### Requirement: Revocation audit event
When the job revokes a superseded credential it SHALL emit an `API_KEY_REVOKED` audit event via `ApiKeyAuditLogger`, containing at least: event type `API_KEY_REVOKED`, `keyId` (the `jti`), `jurisdictionId`, `revokedBy` (`system:grace-revocation`), `supersededBy` (the renewing key's `jti`), and `timestamp`. The event SHALL NOT contain any token string or secret material.

#### Scenario: Audit event emitted on revocation
- **WHEN** the job revokes a credential with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_REVOKED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `revokedBy = system:grace-revocation`, `supersededBy = K2`, and a timestamp, and no token or secret material

### Requirement: Cross-instance cache eviction on revocation
After revoking a credential in DynamoDB, the job SHALL propagate the revocation to every Hub instance's credential cache using the existing refresh mechanism (`RefreshQueueService` carrying the revoked `jti`, the same path used by Config Console's manual revoke), and SHALL evict the credential from the local cache. This ensures no Hub instance can re-validate the revoked key from a warm cache.

#### Scenario: Revoked key is evicted everywhere
- **WHEN** the job revokes a credential with `jti = K1`
- **THEN** a refresh carrying `jti = K1` is published so that every Hub instance evicts `K1` from its credential cache, and the running instance evicts `K1` locally

### Requirement: Operational visibility — per-run counts
Each execution of the job SHALL log, at a level visible in CloudWatch, the number of candidate keys evaluated and the number revoked in that run.

#### Scenario: Counts logged each run
- **WHEN** a cycle evaluates 5 candidate keys and revokes 2
- **THEN** the run logs a structured record indicating 5 evaluated and 2 revoked

### Requirement: Failure detection and manual remediation
The job's execution SHALL be observable such that a failure to run (unhandled error, or a missed run within the expected window) can raise an alert in the monitoring system. A CloudWatch log-based alarm SHALL be defined for this condition, and the operations runbook SHALL document a manual remediation procedure for revoking expired-grace keys when the job is not running.

#### Scenario: Job failure raises an alert
- **GIVEN** the scheduled job fails to run or errors
- **WHEN** the failure is detected via logs/heartbeat
- **THEN** a CloudWatch alarm is raised and the operations runbook specifies the manual remediation procedure
