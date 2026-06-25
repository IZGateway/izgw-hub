## ADDED Requirements

### Requirement: ApiKeyCredential entity structure
`ApiKeyCredential` SHALL be a DynamoDB entity following Hub's single-table design. It SHALL extend `DynamoDbAudit` and be annotated with `@DynamoDbBean`. Its sort key SHALL be `{env}#{jti}`, where `env` is the environment name and `jti` is the UUID token identifier.

Required fields:
- `jti` — String; the JWT `jti` claim; unique credential identifier
- `env` — String; the environment name (e.g., `Production`, `Onboarding`)
- `status` — String; one of `active`, `revoked`, `expired`
- `jurisdictionId` — String; the jurisdiction the credential was issued to (from JWT `sub`); stored as a string representation of an integer to match the legacy IZG jurisdiction identifier scheme (e.g., `"42"`)
- `issuedAt` — `Instant`; when the credential was issued (serialized via `InstantAsStringAttributeConverter`)
- `expiresAt` — `Instant`; when the credential expires (serialized via `InstantAsStringAttributeConverter`)
- `revokedAt` — `Instant` (nullable); when the credential was revoked
- `revokedBy` — String (nullable); identity of the revoking operator

#### Scenario: Entity persisted by Config Console is readable by Hub
- **WHEN** Config Console writes an `ApiKeyCredential` record with `status = active` using sort key `Production#<jti>`
- **THEN** Hub's repository can read that record by `env = Production` and `jti = <jti>` and deserialize it without error

### Requirement: ApiKeyCredential sort key format
The `sortKey` SHALL be formatted as `{env}#{jti}` where `{env}` is the runtime environment string and `{jti}` is the credential's UUID. This format is consistent with Hub's single-table design and allows Hub to scope lookups to the current environment by prefix.

#### Scenario: Sort key for Production environment
- **WHEN** an `ApiKeyCredential` is created for `env = Production` and `jti = 018f4e2a-5678-7abc-8def-000000000002`
- **THEN** its `sortKey` value is `Production#018f4e2a-5678-7abc-8def-000000000002`

### Requirement: Instant date serialization
`issuedAt` and `expiresAt` fields of type `Instant` SHALL be serialized to DynamoDB as ISO-8601 strings with UTC `Z` suffix (e.g., `2025-06-04T00:00:00Z`) using `InstantAsStringAttributeConverter`. This is distinct from the `Date` fields inherited from `DynamoDbAudit` (which use a custom `DateConverter` with millisecond format `yyyy-MM-dd'T'HH:mm:ss.SSSXX`). Both formats must coexist without conflict.

#### Scenario: Instant round-trip through DynamoDB
- **WHEN** an `ApiKeyCredential` is written with `issuedAt = 2025-06-04T00:00:00Z`
- **THEN** reading it back from DynamoDB returns an `Instant` equal to `2025-06-04T00:00:00Z` (no precision loss, no null)

### Requirement: ApiKeyCredentialRepository lookup by env and jti
`ApiKeyCredentialRepository` SHALL provide a `findByEnvAndJti(String env, String jti)` method that returns `Optional<ApiKeyCredential>`. The method SHALL construct the sort key as `{env}#{jti}` and perform a DynamoDB `GetItem` using the `DynamoDbEnhancedClient`. An empty `Optional` SHALL be returned if no record exists.

#### Scenario: Active credential lookup
- **WHEN** `findByEnvAndJti("Production", "<jti>")` is called and a matching active record exists
- **THEN** the method returns `Optional.of(credential)` with `status = active`

#### Scenario: Missing credential lookup
- **WHEN** `findByEnvAndJti("Production", "<unknown-jti>")` is called and no matching record exists
- **THEN** the method returns `Optional.empty()`

#### Scenario: Revoked credential lookup
- **WHEN** `findByEnvAndJti("Production", "<jti>")` is called and the record has `status = revoked`
- **THEN** the method returns `Optional.of(credential)` with `status = revoked`
