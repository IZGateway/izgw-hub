## ADDED Requirements

### Requirement: Scheduled grace-period revocation sweep
Hub SHALL run a scheduled, in-process job that periodically revokes superseded API-key credentials whose grace period has expired. On each cycle the job SHALL query for candidates (`status == grace_period`, non-null `graceExpiresAt`, `graceExpiresAt <= now`) across all `ApiKeyCredential` records and, for each candidate, transition it to `revoked`.

The run interval SHALL be configurable (`apikey.grace-revocation.*`), and the job SHALL be guarded so that, in a multi-instance Hub deployment, a single instance performs revocation per cycle.

#### Scenario: Grace period has passed
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` timestamp has passed
- **WHEN** the scheduled job runs
- **THEN** the key's `status` is set to `revoked` in DynamoDB, `revokedAt` is set to the current time, `revokedBy` is set to `system:grace-revocation`, and a revocation audit event is emitted

#### Scenario: Grace period has not passed
- **GIVEN** a renewed key in `status = grace_period` whose `graceExpiresAt` has not yet passed
- **WHEN** the scheduled job runs
- **THEN** the key is not revoked and remains `grace_period` (and continues to authenticate)

#### Scenario: Idempotent re-run
- **GIVEN** a key was already revoked by a previous cycle
- **WHEN** the scheduled job runs again
- **THEN** the key is not re-written and no duplicate revocation audit event is emitted for it

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
The job's execution SHALL be observable such that a failure to run (unhandled error, or a missed run within the expected window) can raise an alert in the monitoring system. A CloudWatch log-based alarm SHALL be defined for this condition, and the operations runbook SHALL document a manual remediation procedure for revoking expired-grace keys when the job is not running.

#### Scenario: Job failure raises an alert
- **GIVEN** the scheduled job fails to run or errors
- **WHEN** the failure is detected via logs/heartbeat
- **THEN** a CloudWatch alarm is raised and the operations runbook specifies the manual remediation procedure
