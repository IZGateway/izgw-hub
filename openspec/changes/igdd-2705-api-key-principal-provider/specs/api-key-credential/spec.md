## ADDED Requirements

### Requirement: ApiKeyCredential entity structure
`ApiKeyCredential` SHALL be a DynamoDB entity following Hub's single-table design. It SHALL extend `DynamoDbAudit` and be annotated with `@DynamoDbBean`. Its sort key SHALL be `{jti}` — the credential's UUID token identifier alone, with no environment prefix. The entity class name (`ApiKeyCredential`) is the `entityType` attribute, per Hub's single-table convention.

Required fields:
- `jti` — String; the JWT `jti` claim; unique credential identifier
- `environments` — DynamoDB **Number Set (`NS`)** of environment IDs (values 1–6 per the IZG `Environment` enumeration), read into `Set<Integer>`; the environments in which the credential is valid. A DynamoDB List (`L`) will NOT deserialize into this property, and no DynamoDB set may be empty, so "no environments" is represented by the attribute being absent (which reads as `null`). Standard credentials contain exactly one ID; admin/operational credentials MAY contain several. Environment authorization is a server-side property read from this list — it is NOT carried in the JWT.
- `status` — String; one of `active`, `revoked` (a renewed key also sits in `grace_period` — see IGDD-2711)
- `jurisdictionId` — String; the jurisdiction the credential was issued to (from JWT `sub`); stored as a string representation of an integer to match the legacy IZG jurisdiction identifier scheme (e.g., `"42"`)
- `issuedAt` — `Instant`; when the credential was issued (serialized via `InstantAsStringAttributeConverter`)
- `expiresAt` — `Instant`; when the credential expires (serialized via `InstantAsStringAttributeConverter`)
- `revokedAt` — `Instant` (nullable); when the credential was revoked
- `revokedBy` — String (nullable); identity of the revoking operator

#### Scenario: Entity persisted by Config Console is readable by Hub
- **WHEN** Config Console writes an `ApiKeyCredential` record with `status = active` using sort key `<jti>`
- **THEN** Hub's repository can read that record by `jti = <jti>` and deserialize it without error

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
