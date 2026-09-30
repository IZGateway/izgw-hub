# Spec: `ApiKeyCredential`

**Component:** `ApiKeyCredential` DynamoDB entity and `ApiKeyCredentialRepository`  
**Implemented in:** `gov.cdc.izgateway.dynamodb.model.ApiKeyCredential`, `gov.cdc.izgateway.dynamodb.repository.ApiKeyCredentialRepository`  
**Related specs:** `api-key-principal-provider`  

---

## Purpose

`ApiKeyCredential` is the credential registry entry for an issued API key in Hub's
single-table DynamoDB design: Config Console writes the record when a key is issued or
revoked, and Hub reads it through `ApiKeyCredentialRepository.findByJti` at
authentication time. The sort key is the credential's `jti` alone, with no environment
prefix. The environments a credential is valid for are a server-side Number Set on the
record rather than a JWT claim, and the `Instant` fields `issuedAt` and `expiresAt`
serialize as ISO-8601 `Z` strings alongside the millisecond-format `Date`
audit fields inherited from `DynamoDbAudit`. A renewed credential sits in `grace_period`
with `graceExpiresAt` and `supersededBy` set by Config Console until Hub's grace-period
sweep terminates it (see `../grace-period-revocation/spec.md`).

---

## Requirements

### Requirement: ApiKeyCredential entity structure
`ApiKeyCredential` SHALL be a DynamoDB entity following Hub's single-table design. It SHALL extend `DynamoDbAudit` and be annotated with `@DynamoDbBean`. Its sort key SHALL be `{jti}` — the credential's UUID token identifier alone, with no environment prefix. The entity class name (`ApiKeyCredential`) is the `entityType` attribute, per Hub's single-table convention.

Required fields:
- `jti` — String; the JWT `jti` claim; unique credential identifier
- `environments` — DynamoDB **Number Set (`NS`)** of environment IDs (values 1–6 per the IZG `Environment` enumeration), read into `Set<Integer>`; the environments in which the credential is valid. A DynamoDB List (`L`) will NOT deserialize into this property, and no DynamoDB set may be empty, so "no environments" is represented by the attribute being absent (which reads as `null`). Standard credentials contain exactly one ID; admin/operational credentials MAY contain several. Environment authorization is a server-side property read from this list — it is NOT carried in the JWT.
- `status` — String; one of `active`, `grace_period`, `revoked`, `expired` (a renewed key sits in `grace_period` during its grace window and still authenticates; `expired` is a terminal state written by the sweep per IGDD-3167 when the key's own `expiresAt` capped its validity before the grace window ended)
- `jurisdictionId` — String; the jurisdiction the credential was issued to (from JWT `sub`); stored as a string representation of an integer to match the legacy IZG jurisdiction identifier scheme (e.g., `"42"`)
- `issuedAt` — `Instant`; when the credential was issued (serialized via `InstantAsStringAttributeConverter`)
- `expiresAt` — `Instant`; when the credential expires (serialized via `InstantAsStringAttributeConverter`)
- `revokedAt` — `Instant` (nullable); when the credential was revoked
- `revokedBy` — String (nullable); identity of the revoking operator (e.g. an operator id for a manual revoke, or `system:grace-revocation` for an automated grace-period revocation)
- `expiredAt` — `Instant` (nullable); when the credential was recorded as expired by the sweep (IGDD-3167). Written instead of `revokedAt` when the terminal status resolves to `expired`, so exactly one of the two timestamp/actor pairs is ever set.
- `expiredBy` — String (nullable); identity recording the expiry, e.g. `system:grace-expiration` (IGDD-3167)
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

### Requirement: ApiKeyCredential sort key format
The `sortKey` SHALL be formatted as `{jti}`, the credential's UUID, with no environment prefix. The Hub reads a credential directly by `jti`; the environments a credential is valid for are stored in the `environments` attribute rather than encoded in the key, so that set can change (subject to access-control review) without rewriting the key.

#### Scenario: Sort key is the jti alone
- **WHEN** an `ApiKeyCredential` is created with `jti = 018f4e2a-5678-7abc-8def-000000000002`
- **THEN** its `sortKey` value is `018f4e2a-5678-7abc-8def-000000000002`

### Requirement: Instant date serialization
`issuedAt` and `expiresAt` fields of type `Instant` SHALL be serialized to DynamoDB as ISO-8601 strings with UTC `Z` suffix (e.g., `2025-06-04T00:00:00Z`) using `InstantAsStringAttributeConverter`. This is distinct from the `Date` fields inherited from `DynamoDbAudit` (which use a custom `DateConverter` with millisecond format `yyyy-MM-dd'T'HH:mm:ss.SSSXX`). Both formats must coexist without conflict.

#### Scenario: Instant round-trip through DynamoDB
- **WHEN** an `ApiKeyCredential` is written with `issuedAt = 2025-06-04T00:00:00Z`
- **THEN** reading it back from DynamoDB returns an `Instant` equal to `2025-06-04T00:00:00Z` (no precision loss, no null)

### Requirement: ApiKeyCredentialRepository lookup by jti
`ApiKeyCredentialRepository` SHALL provide a `findByJti(String jti)` method that returns `Optional<ApiKeyCredential>`. The method SHALL use the sort key `{jti}` and perform a DynamoDB `GetItem` using the `DynamoDbEnhancedClient`. An empty `Optional` SHALL be returned if no record exists.

#### Scenario: Active credential lookup
- **WHEN** `findByJti("<jti>")` is called and a matching active record exists
- **THEN** the method returns `Optional.of(credential)` with `status = active`

#### Scenario: Missing credential lookup
- **WHEN** `findByJti("<unknown-jti>")` is called and no matching record exists
- **THEN** the method returns `Optional.empty()`

#### Scenario: Revoked credential lookup
- **WHEN** `findByJti("<jti>")` is called and the record has `status = revoked`
- **THEN** the method returns `Optional.of(credential)` with `status = revoked`

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
