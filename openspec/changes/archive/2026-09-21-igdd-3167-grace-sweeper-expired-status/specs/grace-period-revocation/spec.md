## RENAMED Requirements
- FROM: `### Requirement: Revocation audit event`
- TO: `### Requirement: Termination audit events`

## MODIFIED Requirements

### Requirement: Termination audit events
When the job terminates a superseded credential it SHALL emit exactly one audit event via `ApiKeyAuditLogger`, matching the terminal status assigned:
- `API_KEY_EXPIRED`, containing at least: event type `API_KEY_EXPIRED`, `keyId` (the `jti`), `jurisdictionId`, `expiredBy` (`system:grace-expiration`), `supersededBy` (the renewing key's `jti`), and `timestamp`; or
- `API_KEY_REVOKED`, containing at least: event type `API_KEY_REVOKED`, `keyId` (the `jti`), `jurisdictionId`, `revokedBy` (`system:grace-revocation`), `supersededBy` (the renewing key's `jti`), and `timestamp`.

Neither event SHALL contain any token string or secret material. The event emitted SHALL correspond to the terminal status actually written by the conditional update; an instance whose conditional write fails SHALL emit no event.

#### Scenario: Expired audit event emitted
- **WHEN** the job marks a credential expired with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_EXPIRED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `expiredBy = system:grace-expiration`, `supersededBy = K2`, and a timestamp, and no token or secret material

#### Scenario: Audit event emitted on revocation
- **WHEN** the job revokes a credential with `jti = K1`, `jurisdictionId = MA`, `supersededBy = K2`
- **THEN** an `API_KEY_REVOKED` event is emitted with `keyId = K1`, `jurisdictionId = MA`, `revokedBy = system:grace-revocation`, `supersededBy = K2`, and a timestamp, and no token or secret material

#### Scenario: Lost conditional write emits no event
- **WHEN** the job's conditional update for `jti = K1` fails because another instance already terminated it
- **THEN** neither an `API_KEY_EXPIRED` nor an `API_KEY_REVOKED` event is emitted by this instance

### Requirement: Operational visibility — per-run counts
Each execution of the job SHALL log, at a level visible in CloudWatch, the number of candidate keys evaluated, the number marked `expired`, and the number marked `revoked` in that run, as `evaluated`, `expired`, and `revoked` fields on the `GRACE_REVOCATION_RUN` event.

#### Scenario: Counts logged each run
- **WHEN** a cycle evaluates 5 candidate keys, marks 2 `expired`, and revokes 1
- **THEN** the run logs a structured `GRACE_REVOCATION_RUN` record indicating 5 evaluated, 2 expired, and 1 revoked
