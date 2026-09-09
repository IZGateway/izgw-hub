## MODIFIED Requirements

### Requirement: ApiKeyCredential entity structure
`ApiKeyCredential` SHALL be a DynamoDB entity following Hub's single-table design. It SHALL extend `DynamoDbAudit` and be annotated with `@DynamoDbBean`. Its sort key SHALL be `ApiKeyCredential#{env}#{jti}`, where `env` is the environment name and `jti` is the UUID token identifier.

Required fields:
- `jti` — String; the JWT `jti` claim; unique credential identifier
- `env` — String; the environment name (e.g., `Production`, `Onboarding`)
- `status` — String; one of `active`, `grace_period`, `revoked`, `expired` (a renewed key sits in `grace_period` during its grace window and still authenticates)
- `jurisdictionId` — String; the jurisdiction the credential was issued to (from JWT `sub`)
- `issuedAt` — `Instant`; when the credential was issued (serialized via `InstantAsStringAttributeConverter`)
- `expiresAt` — `Instant`; when the credential expires (serialized via `InstantAsStringAttributeConverter`)
- `revokedAt` — `Instant` (nullable); when the credential was revoked (set only when `status` transitions to `revoked`)
- `revokedBy` — String (nullable); identity of the revoking operator (e.g. an operator id for a manual revoke, or `system:grace-revocation` for an automated grace-period revocation)
- `expiredAt` — `Instant` (nullable); when the credential was marked expired (set only when `status` transitions to `expired`, e.g. `system:grace-expiration` for the automated grace-period sweep)
- `expiredBy` — String (nullable); identity/process that recorded the expiry
- `graceExpiresAt` — `Instant` (nullable); set by Config Console at renewal on the superseded (old) credential; the instant after which a superseded credential becomes eligible for automated revocation. Serialized via `InstantAsStringAttributeConverter`.
- `supersededBy` — String (nullable); set by Config Console at renewal on the old credential; the `jti` of the renewed credential that replaced it.

`graceExpiresAt` and `supersededBy` are written by Config Console's renewal route and read by Hub; records that predate renewal have `null` for both and SHALL deserialize without error. `expiredAt`/`expiredBy` are written only by Hub's grace-period sweep (IGDD-3167); a credential SHALL NOT have both `revokedAt` and `expiredAt` populated.

#### Scenario: Entity persisted by Config Console is readable by Hub
- **WHEN** Config Console writes an `ApiKeyCredential` record with `status = active` using sort key `ApiKeyCredential#Production#<jti>`
- **THEN** Hub's repository can read that record by `env = Production` and `jti = <jti>` and deserialize it without error

#### Scenario: Superseded credential carries grace fields
- **WHEN** Config Console renews a credential and writes the old record with `status = grace_period`, `supersededBy = <new-jti>`, and `graceExpiresAt = 2026-07-01T00:00:00Z`
- **THEN** Hub's repository reads the record back with `graceExpiresAt` equal to `2026-07-01T00:00:00Z` and `supersededBy` equal to `<new-jti>` (no precision loss, no null)

#### Scenario: Legacy record without grace fields
- **WHEN** Hub reads an `ApiKeyCredential` that was written before grace fields existed
- **THEN** `graceExpiresAt` and `supersededBy` are `null` and the record deserializes without error

#### Scenario: Legacy record without expired fields
- **WHEN** Hub reads an `ApiKeyCredential` that was written before `expiredAt`/`expiredBy` existed
- **THEN** `expiredAt` and `expiredBy` are `null` and the record deserializes without error
