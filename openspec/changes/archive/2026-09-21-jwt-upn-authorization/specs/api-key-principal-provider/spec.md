## MODIFIED Requirements

### Requirement: DynamoDB credential status check
When the credential cache misses, Hub SHALL read the `ApiKeyCredential` record and act on its `status`:
- **active** or **grace_period**: validate that the request's target environment (`SystemUtils.getDestType()`) is contained in the credential's server-side `environments` list; if it is not, record the `jti` in the absent cache (so an `environments` edit takes effect within the credential-cache TTL) and throw `ApiKeyAuthenticationException`. Otherwise construct an `ApiKeyPrincipal` from JWT claims (`upn` → `name` (IzgPrincipal.getName()), `sub` → `organization` (a numeric string jurisdiction ID, e.g., `"42"`), `jti` → jti), store in credential cache with `jwt.credential-cache-ttl` (default 5 minutes), and return the principal. The `roles` field on the returned principal SHALL be empty; JWT claims SHALL NOT be used to populate roles.
- **revoked** (any non-usable status): store a REVOKED sentinel in the revoked cache with TTL equal to the maximum possible token lifetime (366 days), and throw `ApiKeyAuthenticationException`.
- **record absent**: record the `jti` in the absent cache with TTL equal to `jwt.credential-cache-ttl` (default 5 minutes), and throw `ApiKeyAuthenticationException`. The shorter TTL lets a credential record created after a cold-cache miss take effect without a permanent lockout.

#### Scenario: Active credential
- **WHEN** DynamoDB returns `status = active` for the `jti` and the request's target environment is in the credential's `environments` list
- **THEN** an `ApiKeyPrincipal` is constructed from JWT claims with `name = upn`, empty `roles`, and cached with 5-minute TTL, then returned

#### Scenario: Credential not valid for the target environment
- **WHEN** DynamoDB returns `status = active` for the `jti` but the request's target environment (`SystemUtils.getDestType()`) is NOT in the credential's `environments` list
- **THEN** the `jti` is recorded in the absent cache and `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`, resulting in 401

#### Scenario: Revoked credential
- **WHEN** DynamoDB returns `status = revoked` for the `jti`
- **THEN** a REVOKED sentinel is cached with max-token-lifetime TTL and `ApiKeyAuthenticationException` is thrown, resulting in 401

#### Scenario: Absent credential record
- **WHEN** DynamoDB has no `ApiKeyCredential` record for `{jti}`
- **THEN** the `jti` is cached in the absent cache (5-minute TTL) and `ApiKeyAuthenticationException` is thrown, resulting in 401

## ADDED Requirements

### Requirement: JWT client authorization via DynamoDB AccessGroup lookup
Hub SHALL authorize JWT clients using the `upn` value as the identity key in DynamoDB AccessGroup lookups, identical to how mTLS cert clients are authorized using the certificate CN. `AccessControlService.isUserInRole()` SHALL NOT contain a special fallback for `ApiKeyPrincipal` instances that reads roles from the JWT token. The DynamoDB AccessGroup table is the sole source of role assignments for both JWT and cert principals.

#### Scenario: JWT client UPN present in AccessGroup
- **WHEN** a JWT client presents a valid token with `upn = immunize.example.gov` AND that UPN is a member of an AccessGroup with role `soap`
- **THEN** the JWT client is authorized to call SOAP endpoints, identical to a cert client with CN `immunize.example.gov` in the same group

#### Scenario: JWT client UPN absent from all AccessGroups
- **WHEN** a JWT client presents a valid token with a `upn` that is not in any AccessGroup
- **THEN** access is denied with 401, identical to a cert client whose CN is not in any group

#### Scenario: JWT client UPN in admin AccessGroup
- **WHEN** a JWT client presents a valid token with a `upn` that is in an AccessGroup with role `admin`
- **THEN** `AccessControlValve.updateRoles()` adds `ADMIN` to `RequestContext.getRoles()`, granting admin privileges identical to an mTLS admin cert client
