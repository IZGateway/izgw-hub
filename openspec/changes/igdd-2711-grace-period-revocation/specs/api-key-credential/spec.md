## MODIFIED Requirements

### Requirement: ApiKeyCredential entity structure
`ApiKeyCredential` SHALL be a DynamoDB entity following Hub's single-table design. It SHALL extend `DynamoDbAudit` and be annotated with `@DynamoDbBean`. Its sort key SHALL be `{jti}` — the credential's UUID token identifier alone, with no environment prefix.

Required fields:
- `jti` — String; the JWT `jti` claim; unique credential identifier
- `environments` — List of numeric environment IDs (`number[]`); the environments in which the credential is valid. Read by Hub at routing time — NOT carried in the JWT.
- `status` — String; one of `active`, `grace_period`, `revoked` (a renewed key sits in `grace_period` during its grace window and still authenticates)
- `jurisdictionId` — String; the jurisdiction the credential was issued to (from JWT `sub`)
- `issuedAt` — `Instant`; when the credential was issued (serialized via `InstantAsStringAttributeConverter`)
- `expiresAt` — `Instant`; when the credential expires (serialized via `InstantAsStringAttributeConverter`)
- `revokedAt` — `Instant` (nullable); when the credential was revoked
- `revokedBy` — String (nullable); identity of the revoking operator (e.g. an operator id for a manual revoke, or `system:grace-revocation` for an automated grace-period revocation)
- `graceExpiresAt` — `Instant` (nullable); set by Config Console at renewal on the superseded (old) credential; the instant after which a superseded credential becomes eligible for automated revocation. Serialized via `InstantAsStringAttributeConverter`.
- `supersededBy` — String (nullable); set by Config Console at renewal on the old credential; the `jti` of the renewed credential that replaced it.

`graceExpiresAt` and `supersededBy` are written by Config Console's renewal route and read by Hub; records that predate renewal have `null` for both and SHALL deserialize without error.

#### Scenario: Entity persisted by Config Console is readable by Hub
- **WHEN** Config Console writes an `ApiKeyCredential` record with `status = active` using sort key `<jti>`
- **THEN** Hub's repository can read that record by `jti = <jti>` and deserialize it without error

#### Scenario: Superseded credential carries grace fields
- **WHEN** Config Console renews a credential and writes the old record with `status = grace_period`, `supersededBy = <new-jti>`, and `graceExpiresAt = 2026-07-01T00:00:00Z`
- **THEN** Hub's repository reads the record back with `graceExpiresAt` equal to `2026-07-01T00:00:00Z` and `supersededBy` equal to `<new-jti>` (no precision loss, no null)

#### Scenario: Legacy record without grace fields
- **WHEN** Hub reads an `ApiKeyCredential` that was written before grace fields existed
- **THEN** `graceExpiresAt` and `supersededBy` are `null` and the record deserializes without error

## ADDED Requirements

### Requirement: ApiKeyCredentialRepository finder for grace-revocation candidates
`ApiKeyCredentialRepository` SHALL provide a method that returns the `ApiKeyCredential` records eligible for automated grace-period revocation: those with `status == grace_period`, a non-null `graceExpiresAt`, and `graceExpiresAt <= now`. Because the sort key is `{jti}` with no environment prefix, the query SHALL scan all `ApiKeyCredential` records (by `entityType`) and filter in memory on `status`/`graceExpiresAt`. Records with a `null` `graceExpiresAt`, or whose `graceExpiresAt` is in the future, or whose `status` is not `grace_period` (e.g. a normal `active` key), SHALL NOT be returned.

#### Scenario: Expired-grace superseded key is selected
- **WHEN** the finder runs and a record exists with `status = grace_period`, `graceExpiresAt = <one hour ago>`
- **THEN** that record is included in the returned candidates

#### Scenario: Grace not yet expired is excluded
- **WHEN** the finder runs and a record exists with `status = grace_period`, `graceExpiresAt = <one hour from now>`
- **THEN** that record is NOT included in the returned candidates

#### Scenario: Normal active key is excluded
- **WHEN** the finder runs and a record exists with `status = active` (and no grace period)
- **THEN** that record is NOT included in the returned candidates

#### Scenario: Already-revoked key is excluded
- **WHEN** the finder runs and a record exists with `status = revoked` and a past `graceExpiresAt`
- **THEN** that record is NOT included in the returned candidates
